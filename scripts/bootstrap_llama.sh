#!/bin/bash

set -e
set -x

if [[ $(uname) == "Darwin" ]]; then
    # OSX
else
    # linux
    apt-get update -y
    apt-get install cmake3 cmake3-data -y

fi

