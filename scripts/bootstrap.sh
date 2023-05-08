#!/bin/bash

set -e
set -x



#Downloading the latest Miniconda installer for macOS. Your architecture may vary.
curl https://repo.anaconda.com/miniconda/Miniconda3-latest-$CONDA_PLATFORM-$ARCH.sh -o miniconda.sh

# silent install
bash ./miniconda.sh -b

~/miniconda/bin/conda create -n cljonda-meta
~/miniconda/bin/conda install -n cljonda-meta conda



