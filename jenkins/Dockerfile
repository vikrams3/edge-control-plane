# Jenkins with the tools the pipeline drives: Docker CLI (host daemon via the socket),
# kubectl and kind (to deploy into the kind cluster). Plugins and the job come from code.
FROM jenkins/jenkins:2.580.1-lts-jdk21
USER root
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg python3 \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update && apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/v1.37.0/bin/linux/${TARGETARCH}/kubectl" \
 && curl -fsSLo /usr/local/bin/kind "https://kind.sigs.k8s.io/dl/v0.33.0/kind-linux-${TARGETARCH}" \
 && chmod +x /usr/local/bin/kubectl /usr/local/bin/kind \
 && git config --system --add safe.directory '*'
# Runs as root so it can use the mounted Docker socket; fine for a local demo, not for a shared server.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
# Outside /var/jenkins_home (a volume), so config changes apply on the next image build
COPY casc.yaml /usr/share/jenkins/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc.yaml
