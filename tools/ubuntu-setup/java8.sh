#!/bin/bash
set -e
set -x

sudo apt-get install -y software-properties-common
sudo add-apt-repository -y ppa:webupd8team/java
sudo apt-get update -y
echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections
#
sudo mkdir -p /var/cache/oracle-jdk8-installer
sudo cp ~/openwhisk/jdk-8u144-linux-x64.tar.gz /var/cache/oracle-jdk8-installer
sudo apt-get install -y oracle-java8-installer
