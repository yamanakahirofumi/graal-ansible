#!/bin/bash
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TARGET_DIR="$PROJECT_ROOT/target/python-packages"

mkdir -p "$TARGET_DIR"

echo "Installing Python dependencies to $TARGET_DIR..."

# Use pip to install requirements
# -t specifies the target directory for installation
# --upgrade ensures we get the latest versions within requirements
python3 -m pip install -r "$PROJECT_ROOT/requirements.txt" -t "$TARGET_DIR" --upgrade --quiet

echo "Python dependencies installed successfully."
