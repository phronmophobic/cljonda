#!/bin/bash

set -e
set -x

if [[ $(uname) == "Darwin" ]]; then
    # OSX
    true
else
    # linux
    sudo apt-get update -y
    sudo apt-get install cmake3 cmake3-data -y
fi

