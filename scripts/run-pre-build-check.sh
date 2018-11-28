#!/bin/bash

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
PLATFORM=""

#if no arguments passed, inform user about possible ones

if [ $# -eq 0 ]; then
  echo -e "${GREEN}This script should be invoked with platform argument: 'android', 'ios' or 'desktop'${NC}"
  exit 1
else
  PLATFORM=$1
fi

node_version=$(node -v)

if [[ $node_version != "v9.3.0" ]]; then
  echo -e "${YELLOW}+ node version $node_version is installed. node version v9.3.0 is recommended.${NC}"
fi

yarn_version=$(yarn -v)
if [[ $yarn_version != "1.12.3" ]]; then
  echo -e "${YELLOW}+ yarn version $yarn_version is installed. yarn version 1.12.3 is recommended.${NC}"
fi

if [[ $PLATFORM == 'android' ]]; then
  _localPropertiesPath=./android/local.properties
  if ! grep -Fq "ndk.dir" $_localPropertiesPath; then
    if [ -z $ANDROID_NDK_HOME ]; then
      echo -e "${GREEN}NDK directory not configured, please run 'make setup' or add the line to ${_localPropertiesPath}!${NC}"
      exit 1
    fi
  fi
fi

echo -e "${GREEN}Finished!${NC}"
