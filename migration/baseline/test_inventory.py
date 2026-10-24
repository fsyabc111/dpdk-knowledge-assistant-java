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


if __name__ == "__main__":
    unittest.main()
