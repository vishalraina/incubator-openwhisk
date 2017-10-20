#!/bin/bash
set -e
set -x

sudo apt-get install -y software-properties-common
sudo apt-add-repository -y ppa:ansible/ansible
sudo apt-get update
sudo apt-get install -y python-dev libffi-dev libssl-dev
sudo pip install markupsafe
#Ansiblae install fails due to crypo issue here is thread which helped to solve it https://github.com/ansible/ansible/issues/31741
sudo pip install --upgrade setuptools
sudo apt-get install libffi-dev libssl-dev
sudo pip install ansible==2.3.0.0
sudo pip install docker==2.2.1
sudo pip install jinja2==2.9.6
sudo pip install httplib2==0.9.2
sudo pip install requests==2.10.0

ansible --version
ansible-playbook --version