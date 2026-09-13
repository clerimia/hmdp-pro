"""Prepare a persistent seckill voucher and JMeter token CSV.

The script intentionally uses the same login path as the pytest fixtures:
send-code -> read the verification code from Redis -> login.  Tokens are
written below target/ by default and are never printed to stdout.
"""
from __future__ import annotations

import argparse
import csv
import sys
from datetime import datetime, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
AUTOTEST = ROOT / "autotest"
if str(AUTOTEST) not in sys.path:
    sys.path.insert(0, str(AUTOTEST))

from api import user_api, voucher_api  # noqa: E402
from common.client import ApiClient  # noqa: E402
from common.config import load_config  # noqa: E402
from common.db import DbHelper  # noqa: E402
from common.keys import login_code, login_code_cooldown, login_token  # noqa: E402
from common.phone_pool import PhonePool  # noqa: E402
from common.redis_helper import RedisHelper  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=1000)
    parser.add_argument("--stock", type=int, default=300)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "target" / "loadtest" / "seckill" / "tokens.csv")
    parser.add_argument("--profile", default="local")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.count <= 0 or args.stock <= 0:
        raise SystemExit("--count and --stock must be positive")

    cfg = load_config(args.profile)
    http = ApiClient(cfg.base_url, timeout=float(cfg.http.timeout))
    db = DbHelper(**cfg.mysql)
    redis_cli = RedisHelper(**cfg.redis)
    try:
        now = datetime.now()
        voucher = {
            "shopId": 1,
            "title": f"loadtest-{now:%Y%m%d-%H%M%S}",
            "subTitle": "JMeter seckill load test",
            "rules": "load test only",
            "payValue": 10000,
            "actualValue": 8000,
            "type": 1,
            "stock": args.stock,
            "beginTime": (now - timedelta(seconds=60)).strftime("%Y-%m-%dT%H:%M:%S"),
            "endTime": (now + timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%S"),
        }
        created = voucher_api.add_seckill_voucher(http, voucher)
        if created.http_status != 200 or not created.data:
            raise RuntimeError(f"create voucher failed: http={created.http_status} body={created.body}")
        voucher_id = int(created.data)
        db.execute(
            "UPDATE tb_seckill_voucher SET stock = %s, "
            "begin_time = DATE_ADD(NOW(), INTERVAL -60 SECOND), "
            "end_time = DATE_ADD(NOW(), INTERVAL 2 HOUR) "
            "WHERE voucher_id = %s",
            (args.stock, voucher_id),
        )
        for key in (
            f"seckill:stock:{voucher_id}",
            f"seckill:meta:{voucher_id}",
            f"seckill:order:{voucher_id}",
            f"seckill:claim:{voucher_id}",
            f"seckill:txn:{voucher_id}",
        ):
            redis_cli.delete(key)
        # Active vouchers fail closed when the Redis stock key is absent.  The
        # pytest fixture intentionally deletes it so a test can control warmup;
        # a standalone load test must finish preparation with a usable key.
        redis_cli.set(f"seckill:stock:{voucher_id}", str(args.stock))

        args.output.parent.mkdir(parents=True, exist_ok=True)
        pool = PhonePool(prefix=str(cfg.phone.prefix), start=int(cfg.phone.start),
                         width=int(cfg.phone.width))
        phones = pool.take(args.count)
        written = 0
        with args.output.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=["token", "phone", "user_id"])
            writer.writeheader()
            for phone in phones:
                if not redis_cli.get(login_code(phone)):
                    redis_cli.delete(login_code_cooldown(phone))
                sent = user_api.send_code(http, phone)
                if sent.http_status != 200 or not sent.body or not sent.body.get("success"):
                    raise RuntimeError(f"send code failed for user index {written}: {sent.body}")
                code = redis_cli.wait_key(login_code(phone), timeout=5)
                logged = user_api.login(http, phone, code)
                if logged.http_status != 200 or not logged.data:
                    raise RuntimeError(f"login failed for user index {written}: {logged.body}")
                token = str(logged.data)
                user_id = redis_cli.hget(login_token(token), "id")
                if not user_id:
                    raise RuntimeError(f"login token has no user id for user index {written}")
                writer.writerow({"token": token, "phone": phone, "user_id": int(user_id)})
                written += 1
                if written % 100 == 0:
                    print(f"logged_in={written}/{args.count}", flush=True)
        print(f"voucher_id={voucher_id}")
        print(f"stock={args.stock}")
        print(f"tokens={written}")
        print(f"token_csv={args.output}")
        return 0
    finally:
        http._session.close()
        db.close()
        redis_cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
