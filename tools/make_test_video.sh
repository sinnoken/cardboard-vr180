#!/bin/bash
# 用法：tools/make_test_video.sh [輸出檔名]（預設 vr180_test_4K.mp4）。需要 ffmpeg 與 DejaVu 字型（Ubuntu：apt install ffmpeg fonts-dejavu-core）。
# 產生 VR180 等距投影左右並排測試片：每眼 1920x1920 = 180°x180°，總共 3840x1920，10 秒。
# 每 30° 一條格線（320px）。畫面中心 = 正前方，左右邊緣 = ±90°，上下邊緣 = 天頂 / 腳下。
set -e
OUT=${1:-vr180_test_4K.mp4}
F=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
t() { echo "drawtext=fontfile=$F:text='$1':fontsize=$2:fontcolor=white:x=$3:y=$4:box=1:boxcolor=black@0.5:boxborderw=12"; }

eye() { # $1 = L 或 R，$2 = 底色
  echo "color=c=$2:s=1920x1920:r=30:d=10,format=yuv420p,\
drawgrid=w=320:h=320:t=3:c=white@0.6,\
drawbox=x=955:y=0:w=10:h=1920:c=yellow:t=fill,\
drawbox=x=0:y=955:w=1920:h=10:c=yellow:t=fill,\
$(t "FRONT" 90 "(w-text_w)/2" 820),\
$(t "$1 EYE" 70 "(w-text_w)/2" 1040),\
$(t "%{eif\:t\:d}.%{eif\:mod(t*10,10)\:d}s" 60 "(w-text_w)/2" 1160),\
$(t "LEFT 60" 60 300 920),\
$(t "RIGHT 60" 60 1520 920),\
$(t "UP 60" 60 "(w-text_w)/2" 300),\
$(t "DOWN 60" 60 "(w-text_w)/2" 1580)"
}

ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "$(eye L 0x203060)" \
  -f lavfi -i "$(eye R 0x602030)" \
  -filter_complex "[0][1]hstack=inputs=2,format=yuv420p" \
  -c:v libx264 -preset medium -crf 23 -profile:v high -level 5.1 \
  -movflags +faststart -an "$OUT"
