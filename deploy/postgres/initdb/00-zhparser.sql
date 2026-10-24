-- 新空库初始化：zhparser 扩展 + zh 中文分词配置（roadmap B2）。
-- 存量库不由本脚本负责（entrypoint 只在空 datadir 上跑）；存量迁移走
-- alembic 0010（backend/alembic/versions/0010_li_fts_zhparser.py），
-- 词性映射与本文件逐字一致，两侧改动必须同步。
-- 词性映射覆盖 zhparser v2.3 词法类型表的全部 26 个类型（以
-- `SELECT * FROM ts_token_type('zhparser')` 为准；scws 更细的词性标签由
-- zhparser 内部归并到这 26 类，映射表写超出集合的类型会直接报错）。
-- 类型名一律双引号引用：避免 as 一类 SQL 保留字冲突，统一引用对全部类型免疫。
CREATE EXTENSION IF NOT EXISTS zhparser;
CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION zh ADD MAPPING FOR
    "a","b","c","d","e","f","g","h","i","j","k","l","m",
    "n","o","p","q","r","s","t","u","v","w","x","y","z"
WITH simple;
