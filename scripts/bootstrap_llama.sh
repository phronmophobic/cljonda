#!/bin/bash

set -e
set -x

if [[ $(uname) == "Darwin" ]]; then
    # OSX
    true
else
    # linux
    sudo apt-get update -y
    sudo apt-get install build-essential software-properties-common -y
    sudo add-apt-repository ppa:ubuntu-toolchain-r/test -y
    sudo apt-get update -y
    sudo apt-get install build-essential software-properties-common -y
    sudo apt-get update
    sudo apt-get install gcc-9 g++-9 -y
    sudo update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-9 60 --slave /usr/bin/g++ g++ /usr/bin/g++-9
    sudo update-alternatives --config gcc 
    sudo apt-get install fontconfig libfontconfig1-dev libglu1-mesa-dev curl zip -y
    sudo apt install cmake  -y


fi

