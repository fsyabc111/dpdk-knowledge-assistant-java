"""离线组合独立采集的双端正式报告；不启动服务、不连接PG、不重定冻结阈值。"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from acceptance.load.run import write_json
from acceptance.load.statistics import compare_pair


def main(args):
    output = Path(args.output).resolve()
    if output.exists() or not any(p.name == ".migration-artifacts" for p in output.parents):
        raise RuntimeError("组合结果须为gitignored新路径，拒绝覆盖")
    reports = [json.loads(Path(path).read_text()) for path in (args.python_report, args.java_report)]
    threshold = json.loads((ROOT/"migration/contracts/thresholds.json").read_text())
    result = compare_pair(*reports, threshold)
    write_json(output, result)
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-report", required=True)
    parser.add_argument("--java-report", required=True)
    parser.add_argument("--output", required=True)
    raise SystemExit(main(parser.parse_args()))
