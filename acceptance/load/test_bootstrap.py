"""LI表bootstrap只调用冻结工厂并清理初始化连接；不启动模型或复制第二份DDL。"""
import sys
from types import ModuleType
import unittest
from unittest.mock import Mock, patch

from acceptance.load.python_server import prepare_node_table


class BootstrapTests(unittest.TestCase):
    def modules(self, store):
        app, services = ModuleType("app"), ModuleType("app.services")
        app.__path__, services.__path__ = [], []
        config, llama = ModuleType("app.config"), ModuleType("app.services.llama_index_service")
        settings = object()
        config.get_settings = lambda: settings
        llama._new_pgvector_store = Mock(return_value=store)
        return {"app": app, "app.services": services, "app.config": config,
                "app.services.llama_index_service": llama}, settings, llama._new_pgvector_store

    def test_real_factory_owns_table_setup_and_engine_is_closed(self):
        store = Mock()
        modules, settings, factory = self.modules(store)
        with patch.dict(sys.modules, modules):
            prepare_node_table()
        factory.assert_called_once_with(settings, perform_setup=True)
        store._initialize.assert_called_once_with()
        store._engine.dispose.assert_called_once_with()

    def test_initialization_failure_is_not_hidden_and_engine_is_closed(self):
        store = Mock()
        store._initialize.side_effect = RuntimeError("synthetic setup failure")
        modules, _, _ = self.modules(store)
        with patch.dict(sys.modules, modules), self.assertRaises(RuntimeError):
            prepare_node_table()
        store._engine.dispose.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
