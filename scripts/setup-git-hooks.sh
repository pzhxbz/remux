#!/bin/sh
set -eu

repo_root=$(git rev-parse --show-toplevel)
git -C "$repo_root" config --local core.hooksPath .githooks

printf 'Configured core.hooksPath=.githooks for %s\n' "$repo_root"
