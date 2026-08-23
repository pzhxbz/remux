#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  targets=(x86_64-unknown-linux-musl aarch64-unknown-linux-musl)
else
  targets=("$@")
fi

for target in "${targets[@]}"; do
  case "$target" in
    x86_64-unknown-linux-musl|aarch64-unknown-linux-musl) ;;
    *)
      echo "unsupported target: $target" >&2
      exit 2
      ;;
  esac

  rustup target add "$target"

  # rustc ≥1.98 passes --fix-cortex-a53-843419 to the linker on aarch64-*-musl,
  # and the ld.lld bundled with zig rejects it. rust-lld (the LLVM lld shipped
  # with the toolchain) accepts it. Force it for the final link; zig still
  # provides the musl sysroot and C toolchain through cargo-zigbuild's cc shim.
  case "$target" in
    aarch64-unknown-linux-musl)
      export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld
      ;;
    x86_64-unknown-linux-musl)
      export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld
      ;;
  esac

  cargo zigbuild --workspace --release --locked --target "$target"

  stage="dist/remux-$target"
  if [[ -e "$stage" ]]; then
    rm -r "$stage"
  fi
  mkdir -p "$stage"
  install -m 0755 "target/$target/release/remux" "$stage/remux"
  install -m 0755 "target/$target/release/remux-client" "$stage/remux-client"
  install -m 0755 "target/$target/release/remux-relay" "$stage/remux-relay"

  for binary in "$stage/remux" "$stage/remux-client" "$stage/remux-relay"; do
    description="$(file -b "$binary")"
    case "$description" in
      *"statically linked"*|*"static-pie linked"*) ;;
      *)
        echo "binary is not statically linked: $binary ($description)" >&2
        exit 1
        ;;
    esac
    if command -v readelf >/dev/null 2>&1 && readelf -l "$binary" | grep -q 'INTERP'; then
      echo "dynamic ELF interpreter found in $binary" >&2
      exit 1
    fi
  done

  archive="dist/remux-$target.tar.gz"
  tar -C dist -czf "$archive" "remux-$target"
  echo "built $archive"
done
