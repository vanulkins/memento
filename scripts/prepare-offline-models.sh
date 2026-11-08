#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
assets_dir="$repo_dir/src/android/app/src/main/assets/models/bundled"
giga_dir="$assets_dir/gigaam"
nemo_dir="$assets_dir/nemotron"
giga_base="https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/322c3b29492673eb7d0b434bfa9dfb8653e34d02"
nemo_base="https://huggingface.co/onnx-community/Nemotron-3-Diarization-ONNX/resolve/main/onnx"

mkdir -p "$giga_dir" "$nemo_dir"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

fetch() {
  target=$1
  url=$2
  expected_sha=$3
  expected_size=$4

  if [ -f "$target" ] && [ "$(wc -c < "$target" | tr -d ' ')" = "$expected_size" ] && [ "$(sha256_file "$target")" = "$expected_sha" ]; then
    printf 'ready: %s\n' "$(basename "$target")"
    return
  fi

  part="$target.part"
  rm -f "$part"
  curl --fail --location --retry 4 --retry-delay 2 --output "$part" "$url"

  actual_size=$(wc -c < "$part" | tr -d ' ')
  actual_sha=$(sha256_file "$part")
  [ "$actual_size" = "$expected_size" ] || { printf 'size mismatch: %s\n' "$target" >&2; exit 1; }
  [ "$actual_sha" = "$expected_sha" ] || { printf 'sha256 mismatch: %s\n' "$target" >&2; exit 1; }
  mv "$part" "$target"
}

fetch "$giga_dir/v3_e2e_rnnt_vocab.txt" "$giga_base/v3_e2e_rnnt_vocab.txt" "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7" "13354"
fetch "$giga_dir/v3_e2e_rnnt_encoder.int8.onnx" "$giga_base/v3_e2e_rnnt_encoder.int8.onnx" "4e0e076a6076cd110277e529b8ac8f32cd5297f7fbebad5341ae8ddb7d00817b" "224570477"
fetch "$giga_dir/v3_e2e_rnnt_decoder.int8.onnx" "$giga_base/v3_e2e_rnnt_decoder.int8.onnx" "89014e134865615b91e037157e46e389b1271e6072460efc010ea08e61e23146" "1159170"
fetch "$giga_dir/v3_e2e_rnnt_joint.int8.onnx" "$giga_base/v3_e2e_rnnt_joint.int8.onnx" "ade116563dbf66e503b0994efab6b5861412743e52bf31c39fc3fffa3783d5d1" "687791"
fetch "$nemo_dir/model.onnx" "$nemo_base/model.onnx" "12a7c98fc1ee6ec98ea728c7ffd9fcf6b245f3c9c1ea9f7c2a7cc47c023e3f9d" "303466"
fetch "$nemo_dir/model.onnx_data" "$nemo_base/model.onnx_data" "c293d9b5930eb9f6172f095ced052c0d1bbdbeb2594a115497583ca209b1dbd6" "398184448"

printf 'offline model assets are ready\n'
