#!/bin/bash

set -e
set -x

if [[ $(uname) == "Darwin" ]]; then
  CONDA_PLATFORM="MacOSX"
else
  CONDA_PLATFORM="Linux"
fi

curl https://repo.anaconda.com/miniconda/Miniconda3-latest-$CONDA_PLATFORM-${arch}.sh -o miniconda.sh

head ./miniconda.sh

chmod 755 ./miniconda.sh
# silent install
bash ./miniconda.sh -b

~/miniconda3/bin/conda create -n cljonda-meta
~/miniconda3/bin/conda install -n cljonda-meta conda



