FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends python3 fontconfig fonts-noto-cjk fonts-arphic-ukai fonts-arphic-uming && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server.py ./
COPY static/ static/
COPY vendor/ofd/scripts/ vendor/ofd/scripts/
COPY vendor/ofd/engine/dist/ vendor/ofd/engine/dist/
ENV PYTHONDONTWRITEBYTECODE=1
USER 10001:10001
EXPOSE 8080
HEALTHCHECK CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=3)"
CMD ["python3", "server.py"]
