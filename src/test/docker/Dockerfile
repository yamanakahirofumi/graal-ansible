FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
    python3 \
    python3-apt \
    openssh-server \
    sudo \
 && rm -rf /var/lib/apt/lists/*

RUN useradd -m -s /bin/bash testuser \
 && echo "testuser:testuser" | chpasswd \
 && usermod -aG sudo testuser \
 && echo "testuser ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers

RUN sed -ri 's/^#?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config \
 && sed -ri 's/^#?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && sed -ri 's/^#?UsePAM .*/UsePAM yes/' /etc/ssh/sshd_config \
 && mkdir -p /run/sshd

EXPOSE 22

CMD ["/usr/sbin/sshd", "-D"]