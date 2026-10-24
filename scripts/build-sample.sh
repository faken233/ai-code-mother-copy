#!/usr/bin/env bash
# 把平台模板 + 样例 schema 组装成一个可直接运行的应用目录
# 用法：scripts/build-sample.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TPL="$ROOT/templates/node-unified"
APP="$ROOT/tmp/demo/expense"

echo "[build] 模板: $TPL"
echo "[build] 目标: $APP"

rm -rf "$APP"
mkdir -p "$APP/public"

cp "$TPL/package.json" "$APP/package.json"
cp "$TPL/vite.config.js" "$APP/vite.config.js"
cp -R "$TPL/runtime/." "$APP/"
cp "$TPL/samples/expense/schema.json" "$APP/schema.json"
cp -R "$TPL/client" "$APP/client"
cp "$TPL/samples/expense/client/src/App.jsx" "$APP/client/src/App.jsx"

cd "$APP"
npm install --no-audit --no-fund --loglevel=error
npm run build --silent

echo "[build] node_modules: $(du -sh node_modules | cut -f1)"
echo "[build] public/dist : $(du -sh public/dist | cut -f1)"
ls public/dist
