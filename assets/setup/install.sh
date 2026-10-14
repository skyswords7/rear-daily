#!/system/bin/sh
# Generated per installation by RearSetup. Run as Android shell/root via Shizuku or ADB.
set -eu
APP=@@APP@@
ID=@@ID@@
THEME_FILES=@@FILES@@
TEMPLATE_HASH=@@HASH@@
EXPECTED_USER=@@USER@@
STAGE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TARGET="$THEME_FILES/RearDaily/$ID"
MODE=install
if [ "$#" -gt 0 ]; then MODE="$1"; fi
PENDING=""
MOVED=""
LOCKED=0
fail() { printf '%s\n' "$*" >&2; printf '%s\n' "$*" > "$STAGE/result"; exit 1; }
cleanup() {
  code=$?
  trap - EXIT HUP INT TERM
  if [ -n "$MOVED" ] && [ ! -e "$TARGET" ]; then mv "$MOVED" "$TARGET" || true; fi
  if [ -n "$PENDING" ] && [ -d "$PENDING" ]; then
    rm -f "$PENDING/rearscreen" "$PENDING/description.xml" "$PENDING/resource.mrm" "$PENDING/owner" \
      "$PENDING/preview/preview_rearscreen_0.png" "$PENDING/preview/preview_rearscreen_dark_0.png"
    rmdir "$PENDING/preview" "$PENDING" 2>/dev/null || true
  fi
  if [ "$LOCKED" = 1 ]; then rmdir "$STAGE/.install-lock" 2>/dev/null || true; fi
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
[ "$(id -u)" = 2000 ] || [ "$(id -u)" = 0 ] || fail '请通过 Shizuku 或电脑 ADB 执行安装。'
[ "$(am get-current-user)" = "$EXPECTED_USER" ] || fail '当前 Android 用户已切换，请回到安装 App 的用户。'
[ ! -L "$THEME_FILES" ] || fail '主题目录不能是符号链接。'
mkdir -p "$THEME_FILES" || fail '无法建立主题目录，此系统版本需要按手动说明处理。'
[ -w "$THEME_FILES" ] || fail '当前授权无权写入主题目录；此系统版本需要按手动说明处理。'
mkdir "$STAGE/.install-lock" 2>/dev/null || fail '另一个安装正在运行，请等待完成。'
LOCKED=1
[ "$(cat "$STAGE/ready" 2>/dev/null)" = "$ID" ] || fail '安装文件未准备完成，请回到 App 重新准备。'
(cd "$STAGE" && sha256sum -c checksums) || fail '安装文件校验失败，请回到 App 重新准备。'
BASE="$THEME_FILES/MIUI/.ai_wallpaper/maml"
if [ "$MODE" = list ]; then
  printf '%s\n' '手动模式候选目录（请核对自己创建的壁纸，不按最新时间猜测）：'
  if [ -d "$BASE" ]; then
    for item in "$BASE"/*; do
      if [ -f "$item/rearscreen" ] && [ -f "$item/description.xml" ]; then printf '%s\n' "$item"; fi
    done
  fi
  exit 0
fi
if [ "$MODE" = manual ] || [ "$MODE" = restore ]; then
  NAME=""
  if [ "$#" -ge 2 ]; then NAME="$2"; fi
  case "$NAME" in ''|*[!a-zA-Z0-9_-]*) fail '请提供 list 中的单个目录名，不要填写路径。';; esac
  [ "$(printf %s "$NAME" | wc -c)" -le 128 ] || fail '目录名过长。'
  TARGET="$BASE/$NAME"
  [ ! -L "$BASE" ] && [ ! -L "$TARGET" ] && [ ! -L "$TARGET/rearscreen" ] || fail '拒绝符号链接目录。'
  [ -f "$TARGET/rearscreen" ] && [ -f "$TARGET/description.xml" ] || fail '所选目录不是完整的背屏模板。'
  BACKUP="$TARGET/rearscreen.reardaily-original"
  [ ! -L "$BACKUP" ] || fail '备份路径不能是符号链接。'
  if [ "$MODE" = restore ]; then
    [ -f "$BACKUP" ] || fail '没有找到原始模板备份。'
    [ ! -e "$TARGET/.reardaily-restore" ] || fail '发现未完成的恢复文件，请先检查该目录。'
    cp "$BACKUP" "$TARGET/.reardaily-restore" || fail '恢复暂存失败。'
    cmp -s "$BACKUP" "$TARGET/.reardaily-restore" || fail '恢复校验失败。'
    mv -f "$TARGET/.reardaily-restore" "$TARGET/rearscreen" || fail '恢复失败，原文件保留。'
    rm -f "$STAGE/installed"
    printf '%s\n' '已恢复原始文件。请在系统中重新选择并应用该壁纸。' > "$STAGE/result"
    cat "$STAGE/result"
    exit 0
  fi
  if [ ! -e "$BACKUP" ]; then
    [ ! -e "$BACKUP.pending" ] || fail '发现未完成的备份，请先检查该目录。'
    cp "$TARGET/rearscreen" "$BACKUP.pending" || fail '备份失败，未替换文件。'
    cmp -s "$TARGET/rearscreen" "$BACKUP.pending" || fail '备份校验失败，未替换文件。'
    mv "$BACKUP.pending" "$BACKUP" || fail '提交备份失败，未替换文件。'
  fi
  [ ! -e "$TARGET/.reardaily-new" ] || fail '发现未完成的暂存文件，请先检查该目录。'
  cp "$STAGE/rearscreen" "$TARGET/.reardaily-new" || fail '写入暂存文件失败。'
  cmp -s "$STAGE/rearscreen" "$TARGET/.reardaily-new" || fail '暂存校验失败。'
  mv -f "$TARGET/.reardaily-new" "$TARGET/rearscreen" || fail '替换失败，原始备份保留。'
elif [ "$MODE" = install ]; then
  PARENT="$THEME_FILES/RearDaily"
  [ ! -L "$THEME_FILES" ] && [ ! -L "$PARENT" ] && [ ! -L "$TARGET" ] || fail '拒绝符号链接目录。'
  mkdir -p "$PARENT" || fail '无法建立独立模板目录。'
  if [ -e "$TARGET" ]; then
    [ "$(cat "$TARGET/owner" 2>/dev/null)" = "$ID" ] || fail '目标目录不属于本次安装，已停止以保护现有文件。'
  fi
  if [ -f "$TARGET/rearscreen" ] && cmp -s "$STAGE/rearscreen" "$TARGET/rearscreen" \
      && cmp -s "$STAGE/description.xml" "$TARGET/description.xml" \
      && cmp -s "$STAGE/resource.mrm" "$TARGET/resource.mrm" \
      && cmp -s "$STAGE/preview.png" "$TARGET/preview/preview_rearscreen_0.png"; then
    printf '%s\n' '模板已存在且校验一致，继续在手机上完成设置。'
  else
    PENDING=$(mktemp -d "$PARENT/.rd-$ID.XXXXXX") || fail '无法创建安装暂存目录。'
    mkdir "$PENDING/preview"
    cp "$STAGE/rearscreen" "$PENDING/rearscreen"
    cp "$STAGE/description.xml" "$PENDING/description.xml"
    cp "$STAGE/resource.mrm" "$PENDING/resource.mrm"
    cp "$STAGE/preview.png" "$PENDING/preview/preview_rearscreen_0.png"
    cp "$STAGE/preview.png" "$PENDING/preview/preview_rearscreen_dark_0.png"
    printf '%s\n' "$ID" > "$PENDING/owner"
    chmod 755 "$PENDING" "$PENDING/preview"
    chmod 644 "$PENDING/rearscreen" "$PENDING/description.xml" "$PENDING/resource.mrm" "$PENDING/owner" "$PENDING/preview/preview_rearscreen_0.png" "$PENDING/preview/preview_rearscreen_dark_0.png"
    cmp -s "$STAGE/rearscreen" "$PENDING/rearscreen" || fail '模板校验失败。'
    cmp -s "$STAGE/description.xml" "$PENDING/description.xml" || fail '描述文件校验失败。'
    cmp -s "$STAGE/resource.mrm" "$PENDING/resource.mrm" || fail '元数据校验失败。'
    cmp -s "$STAGE/preview.png" "$PENDING/preview/preview_rearscreen_0.png" || fail '预览图校验失败。'
    if [ -e "$TARGET" ]; then
      BACKUP="$TARGET.backup.$(date +%s).$$"
      [ ! -e "$BACKUP" ] || fail '备份目录已存在，未替换模板。'
      mv "$TARGET" "$BACKUP" || fail '保留旧版本失败，未替换模板。'
      MOVED="$BACKUP"
    fi
    mv "$PENDING" "$TARGET" || fail '提交失败，将恢复旧版本。'
    PENDING=""
    MOVED=""
  fi
else
  fail '未知操作。可用操作：install、list、manual 目录名、restore 目录名。'
fi
cmp -s "$STAGE/rearscreen" "$TARGET/rearscreen" || fail '安装后校验失败。'
printf '%s\n%s\n%s\n' "$ID" "$TEMPLATE_HASH" "$TARGET" > "$STAGE/installed.tmp"
mv -f "$STAGE/installed.tmp" "$STAGE/installed"
if [ "$MODE" = manual ]; then
  printf '%s\n' '模板已替换，原始备份已保留。请回到系统设置，重新选择并应用原占位壁纸。' > "$STAGE/result"
  cat "$STAGE/result"
  exit 0
fi
printf '%s\n' '模板准备完成。首次请在系统预览中点击“应用”；已添加过则在系统列表选用“每日背屏”。' > "$STAGE/result"
cat "$STAGE/result"
if [ "${REAR_SETUP_NO_LAUNCH:-0}" != 1 ]; then
  am start --user "$EXPECTED_USER" -f 0x24000000 -n "$APP/.MainActivity" --es rear_setup "$ID" \
  || printf '%s\n' '请手动回到每日背屏 → 设置 → 背屏设置与使用指南 → 打开系统预览。'
fi
