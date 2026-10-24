#!/usr/bin/env python3
"""静态枚举冻结 Python 副本；不导入应用、不读取真实环境、不连接外部服务。"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import sys
import sysconfig

ROOT = Path(__file__).resolve().parents[2]
VERSION = "p0-v1"
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace", "api_route", "websocket"}


def expr(node):
    return ast.unparse(node) if node is not None else None


def literal(node, default=None):
    try:
        return ast.literal_eval(node)
    except (ValueError, TypeError, SyntaxError):
        return default


def location(path, node, **extra):
    return {"path": path.as_posix(), "line": node.lineno, "end_line": getattr(node, "end_lineno", node.lineno), **extra}


def keywords(call):
    return {kw.arg: kw.value for kw in call.keywords}


def all_functions(tree):
    return [n for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]


def route_inventory(trees):
    routes, registrations, unresolved = [], [], []
    included = set()
    for p, tree in trees.items():
        for n in ast.walk(tree):
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == "include_router":
                included.add(expr(n.args[0]))
                registrations.append(location(p, n, kind="include_router", expression=expr(n)))
                if any(k.arg in {"prefix", "dependencies"} for k in n.keywords):
                    unresolved.append(location(p,n,kind="include_router_overrides_require_review",expression=expr(n)))
    for p, tree in trees.items():
        routers = {}
        for node in tree.body:
            if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call):
                call = node.value
                if expr(call.func) in {"APIRouter", "FastAPI"}:
                    kw = keywords(call)
                    for target in node.targets:
                        routers[expr(target)] = {"prefix": literal(kw.get("prefix"), ""), "kind": expr(call.func)}
        for fn in all_functions(tree):
            for decorator in fn.decorator_list:
                if not isinstance(decorator, ast.Call) or not isinstance(decorator.func, ast.Attribute):
                    continue
                receiver, method = expr(decorator.func.value), decorator.func.attr
                if method not in METHODS:
                    continue
                if receiver not in routers:
                    unresolved.append(location(p, decorator, kind="unknown_route_receiver", expression=expr(decorator)))
                    continue
                kw = keywords(decorator)
                route_path = literal(decorator.args[0]) if decorator.args else literal(kw.get("path"))
                if route_path is None:
                    unresolved.append(location(p, decorator, kind="nonliteral_route", expression=expr(decorator)))
                    continue
                prefix = routers[receiver]["prefix"]
                if routers[receiver]["kind"] == "APIRouter" and f"{p.stem}.{receiver}" not in included:
                    unresolved.append(location(p,decorator,kind="unmounted_router",expression=expr(decorator)))
                methods = literal(kw.get("methods"), ["GET"]) if method == "api_route" else [method.upper()]
                deps = []
                args = fn.args.posonlyargs + fn.args.args
                defaults = [None] * (len(args) - len(fn.args.defaults)) + fn.args.defaults
                params = [{"name": a.arg, "annotation": expr(a.annotation), "default": expr(d)} for a, d in zip(args, defaults)]
                params += [{"name": a.arg, "annotation": expr(a.annotation), "default": expr(d)} for a, d in zip(fn.args.kwonlyargs, fn.args.kw_defaults)]
                for n in ast.walk(fn):
                    if isinstance(n, ast.Call) and expr(n.func) == "Depends":
                        deps.append(expr(n))
                for n in ast.walk(decorator):
                    if isinstance(n, ast.Call) and expr(n.func) == "Depends":
                        deps.append(expr(n))
                errors = []
                for n in ast.walk(fn):
                    if isinstance(n, ast.Call) and expr(n.func) == "HTTPException":
                        errors.append(location(p, n, expression=expr(n)))
                for m in methods:
                    routes.append(location(p, fn, id=f"{m} {prefix}{route_path}", method=m, path_template=prefix + route_path,
                        registration_line=decorator.lineno, registration_kind="decorator", handler=fn.name,
                        include_in_schema=literal(kw.get("include_in_schema"), True),
                        response_model=expr(kw.get("response_model")), return_annotation=expr(fn.returns),
                        status_code=expr(kw.get("status_code")) or "200", parameters=params, dependencies=sorted(set(deps)),
                        local_http_errors=errors,
                        return_expressions=[location(p, n, expression=expr(n.value)) for n in ast.walk(fn) if isinstance(n, ast.Return)],
                        calls=sorted({expr(n.func) for n in ast.walk(fn) if isinstance(n, ast.Call)})))
        for n in ast.walk(tree):
            if not isinstance(n, ast.Call):
                continue
            attr = n.func.attr if isinstance(n.func, ast.Attribute) else None
            if attr in {"add_api_route", "add_route", "add_websocket_route", "mount"}:
                unresolved.append(location(p, n, kind="dynamic_registration", expression=expr(n)))
            elif attr in {"append","extend","insert"} and expr(n.func.value).endswith(".routes"):
                unresolved.append(location(p,n,kind="direct_routes_mutation",expression=expr(n)))
            elif attr == "expose":
                kw = keywords(n)
                if expr(n.func.value).startswith("Instrumentator()") and literal(kw.get("endpoint")) == "/metrics":
                    routes.append(location(p, n, id="GET /metrics", method="GET", path_template="/metrics", registration_kind="instrumentator.expose",
                        handler="prometheus_fastapi_instrumentator.exposition", include_in_schema=False,
                        response_model=None, return_annotation=None, status_code="200", parameters=[], dependencies=[], local_http_errors=[],
                        return_expressions=[], calls=[]))
                    registrations.append(location(p, n, kind="instrumentator.expose", expression=expr(n)))
                else:
                    unresolved.append(location(p, n, kind="unknown_expose", expression=expr(n)))
            elif expr(n.func) == "FastAPI":
                kw = keywords(n)
                defaults = [("openapi_url", "/openapi.json"), ("docs_url", "/docs"), ("redoc_url", "/redoc"), ("swagger_ui_oauth2_redirect_url", "/docs/oauth2-redirect")]
                for key, default in defaults:
                    target = literal(kw.get(key), default)
                    if target:
                        for m in ["GET", "HEAD"]:
                            routes.append(location(p, n, id=f"{m} {target}", method=m, path_template=target, registration_kind="FastAPI.default",
                                handler=f"FastAPI.{key}", include_in_schema=False, response_model=None, return_annotation=None,
                                status_code="200", parameters=[], dependencies=[], local_http_errors=[], return_expressions=[], calls=[]))
    return sorted(routes, key=lambda r: r["id"]), registrations, unresolved


def schemas_inventory(trees):
    """解析本地继承闭包和导入别名；不通过导入 Pydantic/应用获取字段。"""
    modules, classes = {}, {}

    def module_name(path):
        parts = list(path.with_suffix("").parts)
        if parts[0] == "backend":
            parts.pop(0)
        if parts[-1] == "__init__":
            parts.pop()
        return ".".join(parts)

    for p, tree in trees.items():
        module = module_name(p)
        package = module if p.stem == "__init__" else module.rpartition(".")[0]
        bindings = {}
        for n in tree.body:
            if isinstance(n, ast.Import):
                for alias in n.names:
                    bindings[alias.asname or alias.name.split(".")[0]] = alias.name if alias.asname else alias.name.split(".")[0]
            elif isinstance(n, ast.ImportFrom):
                if n.level:
                    prefix = package.split(".")[:len(package.split(".")) - n.level + 1]
                    imported = ".".join(prefix + ([n.module] if n.module else []))
                else:
                    imported = n.module or ""
                for alias in n.names:
                    if alias.name != "*":
                        bindings[alias.asname or alias.name] = f"{imported}.{alias.name}".strip(".")
            elif isinstance(n, ast.Assign) and isinstance(n.value, (ast.Name, ast.Attribute)):
                for target in n.targets:
                    if isinstance(target, ast.Name):
                        bindings[target.id] = f"@local:{expr(n.value)}"
            elif isinstance(n, ast.ClassDef):
                symbol = f"{module}.{n.name}" if module else n.name
                classes[symbol] = (p, n, module)
        modules[module] = bindings

    def resolve_text(module, text, trail=()):
        key = (module, text)
        if key in trail:
            raise ValueError(f"模型别名循环: {module}:{text}")
        local = f"{module}.{text}" if module else text
        if local in classes:
            return local
        root, dot, rest = text.partition(".")
        binding = modules.get(module, {}).get(root)
        if binding:
            if binding.startswith("@local:"):
                expanded = resolve_text(module, binding[len("@local:"):], trail + (key,))
            else:
                expanded = binding
            text = expanded + (dot + rest if dot else "")
        if text in classes:
            return text
        # 导入的模块可以再次 re-export 某个父类，继续静态跟随绑定。
        target_module, _, target_name = text.rpartition(".")
        if target_module in modules and target_name in modules[target_module]:
            return resolve_text(target_module, target_name, trail + (key,))
        return text

    def resolve_base(module, node):
        if isinstance(node, ast.Subscript):
            return resolve_base(module, node.value)
        return resolve_text(module, expr(node))

    base_symbols = {symbol:[resolve_base(module,b) for b in node.bases]
                    for symbol,(_,node,module) in classes.items()}
    mro_cache = {}

    def static_mro(symbol, trail=()):
        if symbol in mro_cache:
            return mro_cache[symbol]
        if symbol in trail:
            raise ValueError(f"模型继承循环: {' -> '.join(trail + (symbol,))}")
        if symbol not in classes:
            return [symbol]  # 外部类保持不透明，绝不导入它求运行时 MRO。
        parents = base_symbols[symbol]
        sequences = [list(static_mro(parent, trail + (symbol,))) for parent in parents] + [list(parents)]
        order = [symbol]
        while any(sequences):
            sequences = [s for s in sequences if s]
            head = next((s[0] for s in sequences if all(s[0] not in other[1:] for other in sequences)), None)
            if head is None:
                raise ValueError(f"模型本地 C3 继承顺序冲突: {symbol}")
            order.append(head)
            for sequence in sequences:
                if sequence[0] == head:
                    sequence.pop(0)
        mro_cache[symbol] = order
        return order

    roots = {"pydantic.BaseModel", "pydantic.main.BaseModel"}
    model_symbols = {symbol for symbol in classes if roots.intersection(static_mro(symbol))}

    def model_id(symbol):
        p,node,_ = classes[symbol]
        return f"{p.as_posix()}::{node.name}"

    def own_fields(symbol):
        p,node,_ = classes[symbol]
        return [location(p,a,name=expr(a.target),annotation=expr(a.annotation),default=expr(a.value))
                for a in node.body if isinstance(a,ast.AnnAssign)]

    models = []
    for symbol,(p,node,module) in classes.items():
        if symbol not in model_symbols:
            continue
        fields = own_fields(symbol)
        effective = {}
        for ancestor in reversed(static_mro(symbol)):
            if ancestor not in classes:
                continue
            for field in own_fields(ancestor):
                effective[field["name"]] = {**field,"declared_in":model_id(ancestor),"inherited":ancestor != symbol}
        references = []
        for field in effective.values():
            declaration_module = module_name(Path(field["path"]))
            annotation = ast.parse(field["annotation"],mode="eval")
            pending = list(ast.walk(annotation))
            while pending:
                item = pending.pop()
                if isinstance(item,ast.Constant) and isinstance(item.value,str):
                    try:
                        pending.extend(ast.walk(ast.parse(item.value,mode="eval")))
                    except SyntaxError:
                        pass
                elif isinstance(item,(ast.Name,ast.Attribute)):
                    target = resolve_text(declaration_module,expr(item))
                    if target in model_symbols:
                        ref = {"field":field["name"],"model_id":model_id(target),"path":field["path"],"line":field["line"]}
                        if ref not in references:
                            references.append(ref)
        models.append(location(p,node,id=model_id(symbol),name=node.name,bases=[expr(b) for b in node.bases],fields=fields,
            resolved_bases=[{"expression":expr(base),"symbol":target,"model_id":model_id(target) if target in model_symbols else None}
                            for base,target in zip(node.bases,base_symbols[symbol])],
            inheritance_mro=[model_id(a) if a in classes else a for a in static_mro(symbol)],
            inherited_fields=[f for f in effective.values() if f["inherited"]],effective_fields=list(effective.values()),
            field_model_references=sorted(references,key=lambda r:(r["field"],r["model_id"]))))
    return models


def settings_inventory(trees):
    settings, direct_env = [], []
    for p, tree in trees.items():
        for n in ast.walk(tree):
            if isinstance(n, ast.ClassDef) and n.name == "Settings" and str(p) == "backend/app/config.py":
                settings = [location(p, a, name=expr(a.target), env_name=expr(a.target).upper(), annotation=expr(a.annotation), default=expr(a.value))
                    for a in n.body if isinstance(a, ast.AnnAssign)]
            if isinstance(n, ast.Call) and expr(n.func) in {"os.getenv", "os.environ.get", "environ.get", "getenv"}:
                direct_env.append(location(p, n, expression=expr(n)))
            if isinstance(n, ast.Subscript) and expr(n.value) in {"os.environ", "environ"}:
                direct_env.append(location(p, n, expression=expr(n)))
    return settings, direct_env


def configuration_metadata(trees):
    p = Path("backend/app/config.py")
    source = (ROOT/p).read_text()
    helpers = [location(p,n,name=n.name,expression=ast.get_source_segment(source,n)) for n in all_functions(trees[p])]
    template = []
    for i,line in enumerate((ROOT / "backend/.env.example").read_text().splitlines(),1):
        m = re.match(r"([A-Z][A-Z0-9_]*)=",line)
        if m:
            template.append({"name":m.group(1),"path":"backend/.env.example","line":i})
    knowledge_keys = []
    p = Path("backend/data/knowledge_sources.yml")
    for i,line in enumerate((ROOT/p).read_text().splitlines(),1):
        m = re.match(r"\s*(?:-\s*)?([A-Za-z_][\w-]*):",line)
        if m:
            knowledge_keys.append({"name":m.group(1),"path":str(p),"line":i})
    return {"helpers_and_validators":helpers,"env_template_keys":template,"knowledge_yaml_keys":knowledge_keys,
        "environment_load_source":{"path":"backend/app/config.py","expression":"SettingsConfigDict(env_file=BACKEND_ROOT / '.env')","java_policy":"禁止照搬 ambient .env/DB 默认值，独立配置 fail-closed"}}


def function_catalog(trees):
    catalog = []
    for p,tree in trees.items():
        if not str(p).startswith("backend/app/"):
            continue
        for fn in all_functions(tree):
            errors = [location(p,n,expression=expr(n)) for n in ast.walk(fn)
                      if isinstance(n,ast.Call) and expr(n.func) == "HTTPException"]
            if errors or str(p).startswith("backend/app/api/"):
                catalog.append(location(p,fn,id=f"{p}::{fn.name}@{fn.lineno}",name=fn.name,
                    http_errors=errors,calls=sorted({expr(n.func) for n in ast.walk(fn) if isinstance(n,ast.Call)})))
    return catalog


def ddl_inventory(trees):
    # 从生产 SQL/Alembic/PG 与 LI 适配源码枚举；SQLite 是非生产对照，不混入生产 schema。
    chunks = []
    for p in sorted((ROOT / "backend/app/storage").glob("*.sql")):
        chunks.append((p.relative_to(ROOT), 1, p.read_text()))
    revisions = []
    for p, tree in trees.items():
        if str(p).startswith("backend/alembic/versions/"):
            vals = {expr(n.targets[0]): literal(n.value) for n in tree.body if isinstance(n, ast.Assign)}
            revisions.append({"path": str(p), "revision": vals.get("revision"), "down_revision": vals.get("down_revision")})
        if str(p).startswith("backend/alembic/") or str(p) in {"backend/app/storage/postgres.py", "backend/app/services/llama_index_service.py", "backend/app/storage/li_retrieval_store.py"}:
            for n in ast.walk(tree):
                if isinstance(n, ast.Constant) and isinstance(n.value, str) and re.search(r"\b(CREATE|ALTER|DROP)\s", n.value, re.I):
                    chunks.append((p, n.lineno, n.value))
    tables, operations = {}, []
    for p, start, sql in chunks:
        # 静态 SQL 片段保留完整结构文本以供 J1-01 核对字段/约束；并非可直接拼接执行的 DDL。
        for m in re.finditer(r"\b(CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.]+)|ALTER\s+TABLE\s+([\w.]+)|CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.]+)|CREATE\s+EXTENSION\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.]+))", sql, re.I):
            line = start + sql[:m.start()].count("\n")
            op = {"path": str(p), "line": line, "operation": m.group(0), "table": m.group(2) or m.group(3), "definition": sql[m.start():].split(";", 1)[0].strip()}
            operations.append(op)
            if m.group(2) or m.group(3):
                name = m.group(2) or m.group(3)
                tables.setdefault(name, {"name": name, "sources": []})["sources"].append({k:v for k,v in op.items() if k != "table"})
    # LlamaIndex PGVectorStore 由外部库动态建表。不能假装静态 SQL 提供其列定义。
    li_tree = trees[Path("backend/app/services/llama_index_service.py")]
    li_calls = [location(Path("backend/app/services/llama_index_service.py"), n, expression=expr(n)) for n in ast.walk(li_tree)
                if isinstance(n, ast.Call) and expr(n.func).endswith("PGVectorStore.from_params")]
    tables["public.data_{llama_index_table}"] = {"name": "public.data_{llama_index_table}", "dynamic": True,
        "default_physical_name": "public.data_li_knowledge_v1", "sources": li_calls,
        "definition_status": "外部 llama-index-vector-stores-postgres 动态表；P1 独立合成 PG 核对列/生成列/索引后才能登记 baseline"}
    tables["alembic_version"] = {"name": "alembic_version", "dynamic": True,
        "sources": [{"path": "backend/alembic/env.py", "line": 1}], "definition_status": "Alembic 元数据版本表，Java 单 owner 接管前保留核验"}
    return sorted(tables.values(), key=lambda x:x["name"]), operations, sorted(revisions, key=lambda r:r["path"])


def dependency_inventory(trees):
    modules = {}
    stdlib = set(getattr(sys, "stdlib_module_names", sys.builtin_module_names))
    stdlib.update(p.stem for p in Path(sysconfig.get_path("stdlib")).iterdir() if p.suffix == ".py" or p.is_dir())
    stdlib.update(p.name.split(".")[0] for p in (Path(sysconfig.get_path("stdlib")) / "lib-dynload").glob("*"))
    stdlib.add("app")
    for p, tree in trees.items():
        for n in ast.walk(tree):
            names = []
            if isinstance(n, ast.Import):
                names = [a.name.split(".")[0] for a in n.names]
            elif isinstance(n, ast.ImportFrom) and not n.level and n.module:
                names = [n.module.split(".")[0]]
            for name in names:
                if name not in stdlib:
                    modules.setdefault(name, []).append(location(p,n))
    req = ROOT / "backend/requirements.txt"
    requirements = [{"path": "backend/requirements.txt", "line": i, "requirement": line.strip()}
        for i,line in enumerate(req.read_text().splitlines(),1) if line.strip() and not line.lstrip().startswith("#")]
    return {"python_requirements": requirements, "import_roots": [{"name":k,"sources":v} for k,v in sorted(modules.items())]}


def check_manifest():
    path = ROOT / "docs/java-migration/baseline-manifest.json"
    data = json.loads(path.read_text())
    failures = []
    for f in data["files"]:
        p = ROOT / f["copied_path"]
        if not p.is_file():
            failures.append({"path":f["copied_path"],"reason":"missing"})
            continue
        content = p.read_bytes()
        if hashlib.sha256(content).hexdigest() != f["sha256"] or len(content) != f["bytes"]:
            failures.append({"path":f["copied_path"],"reason":"content_or_size_mismatch"})
        if bool(p.stat().st_mode & 0o111) != f["executable"]:
            failures.append({"path":f["copied_path"],"reason":"executable_mismatch"})
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    expected = "29dc72e7f19c949f9d70b642e3e085a98bd26b1ca2a320fc0be43b5461d7e60c"
    if digest != expected:
        failures.append({"path":str(path.relative_to(ROOT)),"reason":"original_manifest_digest_mismatch"})
    return {"manifest_path":str(path.relative_to(ROOT)),"manifest_sha256":digest,"frozen_git_commit":"80b29135faddd16d5e39fdbf0633291ea6a20489",
        "original_source_head":data["source_head"],"snapshot_semantics":"source HEAD + copied dirty/untracked files; never read original source root",
        "files_checked":len(data["files"]),"intentional_mappings":[{"source_path":f["source_path"],"copied_path":f["copied_path"]} for f in data["files"] if f["source_path"] != f["copied_path"]],
        "failures":failures,"passed":not failures}


def inventory():
    paths = sorted((ROOT / "backend/app").rglob("*.py")) + sorted((ROOT / "backend/alembic").rglob("*.py"))
    trees = {p.relative_to(ROOT):ast.parse(p.read_text(),filename=str(p.relative_to(ROOT))) for p in paths}
    routes, registrations, unresolved = route_inventory(trees)
    settings, direct_env = settings_inventory(trees)
    tables, ddl, revisions = ddl_inventory(trees)
    return {"version":VERSION,"scan_mode":"static_ast_and_sql_only","routes":routes,"route_registrations":registrations,"unresolved_registrations":unresolved,
        "models":schemas_inventory(trees),"settings":settings,"direct_environment_access":direct_env,"configuration_metadata":configuration_metadata(trees),
        "production_tables":tables,"ddl_operations":ddl,"alembic_revisions":revisions,"dependencies":dependency_inventory(trees),"manifest":check_manifest()}


def coverage(data, contracts):
    errors = []
    report = {"version":VERSION,"manifest":data["manifest"]}
    for inv_key,map_key,id_key in [("routes","routes","id"),("production_tables","tables","name"),("settings","settings","name"),("models","models","id")]:
        wanted = [x[id_key] for x in data[inv_key]]
        actual = [x[id_key] for x in contracts[map_key]]
        missing, extra = sorted(set(wanted)-set(actual)), sorted(set(actual)-set(wanted))
        duplicated = sorted(x for x in set(actual) if actual.count(x)>1)
        bad = [x[id_key] for x in contracts[map_key] if not x.get("owner_task") or not x.get("java_target")]
        report[map_key] = {"enumerated":len(wanted),"mapped":len(actual),"coverage_percent":100.0 if not missing and wanted else 0.0,"missing":missing,"extra":extra,"duplicates":duplicated,"unowned":bad}
        if missing or extra or duplicated or bad:
            errors.append(map_key)
    if data["unresolved_registrations"]:
        errors.append("unresolved_registrations")
    if not data["manifest"]["passed"]:
        errors.append("manifest")
    report["unresolved_registrations"] = data["unresolved_registrations"]
    mapped_env = {s["environment_name"] for s in contracts["settings"]}
    template_keys = {s["name"] for s in data["configuration_metadata"]["env_template_keys"]}
    report["environment_template"] = {"enumerated":len(template_keys),"mapped":len(template_keys & mapped_env),"unmapped":sorted(template_keys-mapped_env)}
    if template_keys-mapped_env:
        errors.append("environment_template")
    if data["direct_environment_access"]:
        errors.append("direct_environment_access_requires_explicit_mapping")
    model_ids = {m["id"] for m in data["models"]}
    mapped_models = {m["id"]:m for m in contracts["models"]}
    definition_keys = ["bases","fields","resolved_bases","inheritance_mro","inherited_fields","effective_fields","field_model_references"]
    definition_differences = [{"id":m["id"],"fields":[k for k in definition_keys if mapped_models[m["id"]].get(k) != m[k]]}
                              for m in data["models"] if m["id"] in mapped_models and any(mapped_models[m["id"]].get(k) != m[k] for k in definition_keys)]
    dangling_references = [{"id":m["id"],"reference":ref["model_id"]} for m in data["models"] for ref in m["field_model_references"] if ref["model_id"] not in model_ids]
    report["model_inheritance"] = {"checked":len(data["models"]),"inherited_models":sum(bool(m["inherited_fields"]) for m in data["models"]),
                                  "definition_differences":definition_differences,"dangling_model_references":dangling_references}
    if definition_differences or dangling_references:
        errors.append("model_inheritance")
    semantic_missing = [{"id":r["id"],"fields":[k for k in ["source","authorization","request","response","errors","side_effects"] if not r.get(k)]}
                        for r in contracts["routes"] if any(not r.get(k) for k in ["source","authorization","request","response","errors","side_effects"])]
    report["route_semantics"] = {"unmapped":semantic_missing,"checked":len(contracts["routes"])}
    if semantic_missing:
        errors.append("route_semantics")
    dep_path = ROOT / "migration/contracts/external-dependencies.json"
    if dep_path.exists():
        external = json.loads(dep_path.read_text())
        for key,id_key in [("python_requirements","requirement"),("import_roots","name")]:
            wanted = {d[id_key] for d in data["dependencies"][key]}
            actual = {d[id_key] for d in external[key]}
            report[key] = {"enumerated":len(wanted),"mapped":len(wanted & actual),"unmapped":sorted(wanted-actual),"extra":sorted(actual-wanted)}
            if wanted != actual:
                errors.append(key)
        if any(not d.get("java_target") or d.get("availability") != "not_verified" for key in ["python_requirements","import_roots"] for d in external[key]):
            errors.append("external_dependency_mapping_or_availability")
        wanted_yaml = {v["name"] for v in data["configuration_metadata"]["knowledge_yaml_keys"]}
        actual_yaml = {v["name"] for v in external.get("knowledge_yaml_keys",[])}
        report["knowledge_yaml"] = {"enumerated":len(wanted_yaml),"mapped":len(wanted_yaml & actual_yaml),"unmapped":sorted(wanted_yaml-actual_yaml)}
        if wanted_yaml != actual_yaml:
            errors.append("knowledge_yaml")
    report["passed"] = not errors
    report["failed_categories"] = errors
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write",action="store_true",help="重建静态清单；不生成或覆盖人工契约映射")
    args = parser.parse_args()
    data = inventory()
    target = ROOT / "migration/baseline/static-inventory.json"
    if args.write:
        target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n")
        (ROOT / "migration/contracts/function-catalog.json").write_text(json.dumps({"version":VERSION,"functions":function_catalog({p.relative_to(ROOT):ast.parse(p.read_text()) for p in sorted((ROOT/'backend/app').rglob('*.py'))})},ensure_ascii=False,indent=2)+"\n")
    elif not target.is_file() or json.loads(target.read_text()) != data:
        print("静态清单与冻结源码不一致，请审查后使用 --write",file=sys.stderr)
        return 2
    generated_catalog = {"version":VERSION,"functions":function_catalog({p.relative_to(ROOT):ast.parse(p.read_text()) for p in sorted((ROOT/'backend/app').rglob('*.py'))})}
    if json.loads((ROOT / "migration/contracts/function-catalog.json").read_text()) != generated_catalog:
        print("helper错误目录与冻结源码不一致",file=sys.stderr)
        return 2
    mapping = ROOT / "migration/contracts/coverage-map.json"
    if not mapping.exists():
        print(json.dumps({"inventory_written":str(target.relative_to(ROOT)),"routes":len(data["routes"]),"tables":len(data["production_tables"]),"settings":len(data["settings"]),"models":len(data["models"]),"unresolved":data["unresolved_registrations"]},ensure_ascii=False))
        return 0 if args.write else 2
    report = coverage(data,json.loads(mapping.read_text()))
    out = ROOT / "docs/java-migration/evidence/J0-01/coverage-report.json"
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({"passed":report["passed"],"counts":{k:v["enumerated"] for k,v in report.items() if isinstance(v,dict) and "enumerated" in v},"failures":report["failed_categories"],"report":str(out.relative_to(ROOT))},ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
