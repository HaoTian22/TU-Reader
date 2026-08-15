#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
上传 transit.db 到 Cloudflare R2（S3 兼容 PUT，纯 Python SigV4 签名，无第三方依赖）。

配置优先级：命令行参数 > 环境变量 > tools/.r2config.json（本地配置文件，已被 .gitignore 排除）。
必需项：account_id / bucket / access_key_id / secret_access_key。
可选项：object_key（默认 transit.db）、public_url（上传后 HEAD 校验，默认
  https://assets2.haotian22.top/transit.db）、cache_control（默认 public, max-age=300, must-revalidate）。

上传前默认校验库内 room_master_table.identity_hash 必须与 App 当前 schema 匹配
（防止把 App 会拒绝的库发布上去），可用 --no-hash-check 跳过。

同时会上传同目录版本 sidecar（<db文件>.version → transit.db.version），供 App OTA
时确定网络库版本；缺失只警告、不阻塞主库发布。

用法：
  python tools/upload_transit_db.py                       # 上传内置 transit.db
  python tools/upload_transit_db.py --file path.db        # 指定文件
  python tools/upload_transit_db.py --check-config        # 只检查配置是否齐全
  python tools/upload_transit_db.py --no-hash-check       # 跳过 identity_hash 校验
"""
import argparse
import datetime
import hashlib
import hmac
import json
import os
import sqlite3
import sys
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(HERE, ".r2config.json")
DEFAULT_FILE = os.path.join(HERE, "../app/src/main/assets/data/transit.db")
DEFAULT_OBJECT = "transit.db"
DEFAULT_PUBLIC_URL = "https://assets2.haotian22.top/transit.db"
DEFAULT_VERSION_OBJECT = "transit.db.version"
DEFAULT_VERSION_PUBLIC_URL = "https://assets2.haotian22.top/transit.db.version"
DEFAULT_HASH = "d655117dc122c44ad0b193eacfbeb8a4"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hmac_sha256(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def signing_key(secret: str, date_stamp: str, region: str, service: str) -> bytes:
    k_date = hmac_sha256(("AWS4" + secret).encode("utf-8"), date_stamp)
    k_region = hmac_sha256(k_date, region)
    k_service = hmac_sha256(k_region, service)
    return hmac_sha256(k_service, "aws4_request")


def load_config(args):
    cfg = {}
    if os.path.isfile(CONFIG_FILE):
        with open(CONFIG_FILE, encoding="utf-8") as f:
            cfg.update({k: v for k, v in json.load(f).items() if v})
    for env_key, key in [("R2_ACCOUNT_ID", "account_id"), ("R2_BUCKET", "bucket"),
                         ("R2_ACCESS_KEY_ID", "access_key_id"), ("R2_SECRET_ACCESS_KEY", "secret_access_key"),
                         ("R2_OBJECT_KEY", "object_key"), ("R2_PUBLIC_URL", "public_url"),
                         ("R2_VERSION_OBJECT", "version_object"), ("R2_VERSION_PUBLIC_URL", "version_public_url"),
                         ("R2_CACHE_CONTROL", "cache_control")]:
        val = os.environ.get(env_key)
        if val:
            cfg[key] = val
    if args.account:
        cfg["account_id"] = args.account
    if args.bucket:
        cfg["bucket"] = args.bucket
    if args.key_id:
        cfg["access_key_id"] = args.key_id
    if args.secret:
        cfg["secret_access_key"] = args.secret
    if args.object:
        cfg["object_key"] = args.object
    if args.public_url:
        cfg["public_url"] = args.public_url
    if args.version_object:
        cfg["version_object"] = args.version_object
    if args.version_public_url:
        cfg["version_public_url"] = args.version_public_url
    if args.cache_control:
        cfg["cache_control"] = args.cache_control
    cfg.setdefault("object_key", DEFAULT_OBJECT)
    cfg.setdefault("public_url", DEFAULT_PUBLIC_URL)
    cfg.setdefault("version_object", DEFAULT_VERSION_OBJECT)
    cfg.setdefault("version_public_url", DEFAULT_VERSION_PUBLIC_URL)
    cfg.setdefault("cache_control", "public, max-age=300, must-revalidate")
    return cfg


def missing(cfg):
    return [k for k in ("account_id", "bucket", "access_key_id", "secret_access_key") if not cfg.get(k)]


def check_identity_hash(path, expected, skip):
    if skip:
        return True
    try:
        db = sqlite3.connect(path)
        actual = db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()
        db.close()
    except sqlite3.Error as e:
        print(f"!! 读取 {path} 失败：{e}")
        return False
    if actual is None:
        print("!! 库中没有 room_master_table.identity_hash，可能不是有效 transit.db")
        return False
    if actual[0] != expected:
        print(f"!! identity_hash 不匹配：库={actual[0]}  期望={expected}")
        print("   App 会拒绝该库的在线更新，已中止上传。确认无误可用 --no-hash-check 强制上传。")
        return False
    return True


def sign_put(account_id, bucket, access_key_id, secret, obj, payload, cache_control):
    host = f"{account_id}.r2.cloudflarestorage.com"
    region, service = "auto", "s3"
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    canonical_uri = "/" + urllib.parse.quote(bucket, safe="") + "/" + urllib.parse.quote(obj, safe="")
    payload_hash = sha256_hex(payload)
    headers = {
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
    canonical_headers = "".join(f"{k}:{v}\n" for k, v in headers.items())
    signed_headers = ";".join(headers.keys())
    canonical_request = "\n".join(["PUT", canonical_uri, "", canonical_headers, signed_headers, payload_hash])
    scope = f"{date_stamp}/{region}/{service}/aws4_request"
    string_to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, sha256_hex(canonical_request.encode("utf-8"))])
    signature = hmac_sha256(signing_key(secret, date_stamp, region, service), string_to_sign).hex()
    headers["authorization"] = (f"AWS4-HMAC-SHA256 Credential={access_key_id}/{scope}, "
                                f"SignedHeaders={signed_headers}, Signature={signature}")
    headers["content-type"] = "application/octet-stream"
    headers["cache-control"] = cache_control
    headers["content-length"] = str(len(payload))
    return host, headers


def main():
    ap = argparse.ArgumentParser(description="上传 transit.db 到 Cloudflare R2")
    ap.add_argument("--file", default=DEFAULT_FILE, help="要上传的 db 文件路径")
    ap.add_argument("--account", help="R2 Account ID")
    ap.add_argument("--bucket", help="R2 Bucket 名")
    ap.add_argument("--key-id", help="R2 Access Key ID")
    ap.add_argument("--secret", help="R2 Secret Access Key")
    ap.add_argument("--object", help="对象键（默认 transit.db）")
    ap.add_argument("--public-url", help="上传后 HEAD 校验的公开 URL")
    ap.add_argument("--version-file", help="版本 sidecar 文件路径（默认 <db文件>.version）")
    ap.add_argument("--version-object", help="版本文件对象键（默认 transit.db.version）")
    ap.add_argument("--version-public-url", help="版本文件上传后 HEAD 校验的公开 URL")
    ap.add_argument("--cache-control", help="Cache-Control 响应头")
    ap.add_argument("--expected-hash", default=DEFAULT_HASH, help="期望的 identity_hash")
    ap.add_argument("--no-hash-check", action="store_true", help="跳过 identity_hash 校验")
    ap.add_argument("--check-config", action="store_true", help="只检查配置是否齐全")
    args = ap.parse_args()

    cfg = load_config(args)
    miss = missing(cfg)
    if args.check_config:
        print("必需配置：account_id / bucket / access_key_id / secret_access_key")
        if miss:
            print("缺失：", ", ".join(miss))
            print(f"（配置文件 {CONFIG_FILE} 或环境变量 R2_*，见脚本头注释）")
            return 1
        print("配置齐全 OK")
        return 0
    if miss:
        print("!! 缺少 R2 配置：", ", ".join(miss))
        print(f"   在 {CONFIG_FILE} 填写或设置环境变量 R2_*（见脚本头注释）。运行 --check-config 检查。")
        return 1

    path = os.path.abspath(args.file)
    if not os.path.isfile(path):
        print(f"!! 文件不存在：{path}")
        return 1
    if not check_identity_hash(path, args.expected_hash, args.no_hash_check):
        return 1

    with open(path, "rb") as f:
        payload = f.read()
    size_mb = len(payload) / 1024 / 1024

    try:
        import requests
    except ImportError:
        print("!! 需要 requests 库：python -m pip install requests")
        return 1

    host, headers = sign_put(cfg["account_id"], cfg["bucket"], cfg["access_key_id"],
                             cfg["secret_access_key"], cfg["object_key"], payload, cfg["cache_control"])
    url = f"https://{host}/{cfg['bucket']}/{cfg['object_key']}"
    print(f"上传 {path} ({size_mb:.2f} MB) -> {url}")
    try:
        resp = requests.put(url, data=payload, headers=headers, timeout=180)
    except requests.RequestException as e:
        print(f"!! 上传失败：{e}")
        return 1
    if resp.status_code not in (200, 201, 204):
        print(f"!! 上传失败 HTTP {resp.status_code}: {resp.text[:500]}")
        return 1
    print(f"上传成功 HTTP {resp.status_code}")

    if cfg.get("public_url"):
        try:
            head = requests.head(cfg["public_url"], timeout=30)
            served = head.headers.get("Content-Length")
            served_mb = int(served) / 1024 / 1024 if served and served.isdigit() else "?"
            print(f"公开地址校验：{cfg['public_url']}  HTTP {head.status_code}  size={served_mb} MB")
            if head.status_code == 200:
                print("在线更新已生效")
            else:
                print(f"!! 公开地址返回 {head.status_code}（可能 CDN 缓存未刷新或域名配置问题）")
        except requests.RequestException as e:
            print(f"!! 公开地址校验失败：{e}")

    # 版本 sidecar 上传（best-effort：缺失只警告，不阻塞主库发布）
    version_path = os.path.abspath(args.version_file or (args.file + ".version"))
    if os.path.isfile(version_path):
        with open(version_path, "rb") as f:
            vpayload = f.read()
        vhost, vheaders = sign_put(cfg["account_id"], cfg["bucket"], cfg["access_key_id"],
                                   cfg["secret_access_key"], cfg["version_object"], vpayload, cfg["cache_control"])
        vurl = f"https://{vhost}/{cfg['bucket']}/{cfg['version_object']}"
        print(f"上传版本 sidecar {version_path} ({len(vpayload)} B) -> {vurl}")
        try:
            vresp = requests.put(vurl, data=vpayload, headers=vheaders, timeout=60)
        except requests.RequestException as e:
            print(f"!! 版本文件上传失败：{e}")
            return 1
        if vresp.status_code not in (200, 201, 204):
            print(f"!! 版本文件上传失败 HTTP {vresp.status_code}: {vresp.text[:300]}")
            return 1
        print(f"版本 sidecar 上传成功 HTTP {vresp.status_code}")
        try:
            vhead = requests.head(cfg["version_public_url"], timeout=30)
            print(f"版本文件公开地址校验：{cfg['version_public_url']}  HTTP {vhead.status_code}")
            if vhead.status_code != 200:
                print("!! 版本文件公开地址非 200（可能 CDN 缓存未刷新）")
        except requests.RequestException as e:
            print(f"!! 版本文件公开地址校验失败：{e}")
    else:
        print(f"!! 警告：版本 sidecar {version_path} 不存在，OTA 版本发现将回退 Last-Modified/当前时间")
    return 0


if __name__ == "__main__":
    sys.exit(main())
