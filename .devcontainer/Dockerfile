# Base for the HPG codespace: Java 21 + Maven (devcontainers image).
FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# The image ships a Yarn apt source whose signing key no longer verifies, so `apt-get update` fails with
# "The repository 'https://dl.yarnpkg.com/debian stable InRelease' is not signed", and the Docker-in-Docker
# and Node features (which run apt-get update) cannot install. Nothing here needs Yarn from apt.
RUN rm -f /etc/apt/sources.list.d/yarn.list /usr/share/keyrings/yarn-archive-keyring.gpg
