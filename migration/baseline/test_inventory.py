"""只测试静态枚举/覆盖护栏；不导入 Python 应用或访问数据库。"""
import ast
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SPEC = importlib.util.spec_from_file_location("p0_static_inventory", Path(__file__).with_name("inventory.py"))
inventory = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inventory)


class InventoryGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.actual = inventory.inventory()
        cls.contracts = json.loads((inventory.ROOT / "migration/contracts/coverage-map.json").read_text())

    def test_real_baseline_and_coverage_pass_without_app_import(self):
        self.assertTrue(inventory.coverage(self.actual,self.contracts)["passed"])
        self.assertEqual(self.actual["manifest"]["files_checked"],378)
        self.assertEqual(len(self.actual["manifest"]["intentional_mappings"]),3)
        self.assertNotIn("app",sys.modules)
        self.assertNotIn("fastapi",sys.modules)

    def test_missing_extra_duplicate_and_unowned_mapping_fail_closed(self):
        for mutate in [lambda m:m["routes"].pop(),lambda m:m["routes"].append({"id":"GET /fake","owner_task":"J0-01","java_target":"fake"}),
                       lambda m:m["tables"].append(copy.deepcopy(m["tables"][0])),lambda m:m["settings"][0].pop("owner_task")]:
            mapping=copy.deepcopy(self.contracts)
            mutate(mapping)
            self.assertFalse(inventory.coverage(self.actual,mapping)["passed"])

    def test_dynamic_registration_requires_review(self):
        for snippet in ["app.add_api_route('/unmapped', handler)","app.router.routes.append(route)","app.include_router(other.router,prefix='/x')"]:
            trees={Path("backend/app/main.py"):ast.parse("from fastapi import FastAPI\napp=FastAPI()\n"+snippet)}
            _,_,unknown=inventory.route_inventory(trees)
            self.assertTrue(unknown,snippet)

    def test_hidden_router_metrics_default_docs_and_head_are_classified(self):
        trees={Path("backend/app/main.py"):ast.parse("from fastapi import FastAPI\napp=FastAPI()\napp.include_router(example.router)\nInstrumentator().instrument(app).expose(app,endpoint='/metrics',include_in_schema=False)"),
               Path("backend/app/api/example.py"):ast.parse("from fastapi import APIRouter\nrouter=APIRouter(prefix='/api')\n@router.get('/hidden',include_in_schema=False)\ndef hidden():\n return {}")}
        routes,_,unknown=inventory.route_inventory(trees)
        self.assertFalse(unknown)
        self.assertEqual({r["id"] for r in routes if r["registration_kind"]=="FastAPI.default"},
                         {f"{m} {p}" for m in ["GET","HEAD"] for p in ["/docs","/redoc","/openapi.json","/docs/oauth2-redirect"]})
        hidden=next(r for r in routes if r["id"]=="GET /api/hidden")
        self.assertFalse(hidden["include_in_schema"])
        self.assertNotIn("HEAD /api/hidden",{r["id"] for r in routes})
        self.assertNotIn("OPTIONS /api/hidden",{r["id"] for r in routes})

    def test_unknown_receiver_and_unmounted_router_fail(self):
        for receiver in ["router","unknown"]:
            trees={Path("backend/app/api/example.py"):ast.parse(f"from fastapi import APIRouter\nrouter=APIRouter()\n@{receiver}.get('/x')\ndef example():\n return {{}}")}
            _,_,unknown=inventory.route_inventory(trees)
            self.assertTrue(unknown)

    def test_real_manager_assignment_includes_own_and_traceable_inherited_fields(self):
        models = {m["name"]:m for m in self.actual["models"]}
        manager = models["ManagerAssignment"]
        self.assertEqual(manager["bases"],["AssignmentInfo"])
        self.assertEqual([f["name"] for f in manager["fields"]],["submission_count","student_count"])
        self.assertEqual(len(manager["inherited_fields"]),10)
        self.assertEqual(len(manager["effective_fields"]),12)
        self.assertTrue(all(f["declared_in"]==models["AssignmentInfo"]["id"] for f in manager["inherited_fields"]))
        self.assertTrue(all(f["path"]=="backend/app/schemas/assignments.py" and f["line"]<manager["line"] for f in manager["inherited_fields"]))
        listing = models["ManagerAssignmentListResponse"]
        self.assertIn(manager["id"],{r["model_id"] for r in listing["field_model_references"]})
        mapping = next(m for m in self.contracts["models"] if m["id"]==manager["id"])
        self.assertEqual(mapping["owner_task"],"J2-03")
        self.assertTrue(mapping["java_target"].endswith(".ManagerAssignment"))

    def test_transitive_models_resolve_aliases_modules_relative_imports_and_reexports(self):
        sources = {
            "backend/app/schemas/base.py": "from pydantic import BaseModel as Model\nimport pydantic as pd\nclass Parent(Model):\n id: str\n title: str='parent'\nclass OtherRoot(pd.BaseModel):\n other: int\nclass BaseModel:\n ignored: int\nclass Plain(BaseModel):\n ignored_too: str\n",
            "backend/app/schemas/middle.py": "from . import base as parent_module\nclass Middle(parent_module.Parent):\n title: str='middle'\n count: int\n",
            "backend/app/schemas/__init__.py": "from .middle import Middle as PublicModel\n",
            "backend/app/api/example.py": "from app.schemas import PublicModel as Alias\nclass Leaf(Alias):\n enabled: bool=True\n"}
        models = {m["name"]:m for m in inventory.schemas_inventory({Path(p):ast.parse(s) for p,s in sources.items()})}
        self.assertEqual(set(models),{"Parent","OtherRoot","Middle","Leaf"})
        leaf = models["Leaf"]
        self.assertEqual(leaf["bases"],["Alias"])
        self.assertEqual(leaf["resolved_bases"][0]["model_id"],models["Middle"]["id"])
        fields = {f["name"]:f for f in leaf["effective_fields"]}
        self.assertEqual(set(fields),{"id","title","count","enabled"})
        self.assertEqual(fields["title"]["declared_in"],models["Middle"]["id"])
        self.assertEqual(fields["title"]["default"],"'middle'")
        self.assertEqual(fields["id"]["path"],"backend/app/schemas/base.py")
        self.assertFalse(fields["enabled"]["inherited"])

    def test_diamond_inheritance_uses_local_c3_precedence(self):
        source = "from pydantic import BaseModel\nclass Root(BaseModel):\n shared: int=1\nclass Left(Root):\n shared: int=2\nclass Right(Root):\n shared: int=3\nclass Leaf(Left,Right):\n leaf: str\n"
        models = {m["name"]:m for m in inventory.schemas_inventory({Path("backend/app/schemas/example.py"):ast.parse(source)})}
        self.assertEqual(models["Leaf"]["inheritance_mro"],
                         [models[n]["id"] for n in ["Leaf","Left","Right","Root"]]+["pydantic.BaseModel"])
        shared = next(f for f in models["Leaf"]["inherited_fields"] if f["name"]=="shared")
        self.assertEqual(shared["declared_in"],models["Left"]["id"])
        self.assertEqual(shared["default"],"2")

    def test_model_mapping_missing_subclass_or_inherited_field_fails(self):
        mapping = copy.deepcopy(self.contracts)
        mapping["models"] = [m for m in mapping["models"] if not m["id"].endswith("::ManagerAssignment")]
        self.assertFalse(inventory.coverage(self.actual,mapping)["passed"])
        mapping = copy.deepcopy(self.contracts)
        next(m for m in mapping["models"] if m["id"].endswith("::ManagerAssignment"))["inherited_fields"].pop()
        result = inventory.coverage(self.actual,mapping)
        self.assertIn("model_inheritance",result["failed_categories"])

    def test_invalid_inheritance_cycle_is_rejected_without_imports(self):
        source = "class First(Second):\n pass\nclass Second(First):\n pass\n"
        with self.assertRaisesRegex(ValueError,"模型继承循环"):
            inventory.schemas_inventory({Path("backend/app/schemas/example.py"):ast.parse(source)})


if __name__ == "__main__":
    unittest.main()
