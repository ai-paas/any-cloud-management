FROM mcr.microsoft.com/devcontainers/java:1-21-bullseye

# Install additional tools
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    git \
    vim \
    nano \
    htop \
    tree \
    jq \
    unzip \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Install Gradle (if not already present)
RUN curl -s "https://get.sdkman.io" | bash \
    && bash -c "source /usr/local/sdkman/bin/sdkman-init.sh && sdk install gradle 8.10.2"

# Install Helm
RUN curl https://get.helm.sh/helm-v3.19.0-linux-amd64.tar.gz -o /tmp/helm.tar.gz \
    && tar -xzf /tmp/helm.tar.gz -C /tmp/ \
    && mv /tmp/linux-amd64/helm /usr/local/bin/helm \
    && chmod +x /usr/local/bin/helm \
    && rm -rf /tmp/helm.tar.gz /tmp/linux-amd64

# Install kubectl
RUN curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
    && install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl \
    && rm kubectl

# Install Docker CLI (for Docker-in-Docker)
RUN curl -fsSL https://get.docker.com -o get-docker.sh \
    && sh get-docker.sh \
    && rm get-docker.sh

# Set working directory
WORKDIR /workspace

# Set environment variables
ENV JAVA_HOME=/usr/local/openjdk-21
ENV PATH=$JAVA_HOME/bin:$PATH
ENV GRADLE_USER_HOME=/workspace/.gradle

# Create gradle directory with proper permissions
RUN mkdir -p /workspace/.gradle && chown -R vscode:vscode /workspace/.gradle

# Switch to vscode user
USER vscode
