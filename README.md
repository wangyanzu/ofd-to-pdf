# OFD 转 PDF

一个极简的 OFD 转 PDF 网页工具。拖入文件或点击选择文件，即可开始转换，完成后自动下载 PDF。

- [项目仓库](https://github.com/wangyanzu/ofd-to-pdf)
- [容器镜像](https://github.com/wangyanzu/ofd-to-pdf/pkgs/container/ofd-to-pdf)
- [镜像构建状态](https://github.com/wangyanzu/ofd-to-pdf/actions)

## 使用方法

1. 打开部署后的网页。
2. 拖入一个 `.ofd` 文件，或点击上传区域选择文件。单文件最大 50 MB。
3. 等待转换完成，浏览器会自动下载同名 PDF。如果自动下载被拦截，点击「下载 PDF」。
4. 点击「转换另一个文件」继续使用。

文件上传至你部署的服务器处理，处理结束后自动清理临时文件。浏览器保留本次结果供再次下载，刷新页面后需要重新上传。

## 使用容器镜像部署

先安装 Docker 和 Docker Compose，然后克隆仓库并启动。

```sh
git clone https://github.com/wangyanzu/ofd-to-pdf.git
cd ofd-to-pdf
docker compose pull
docker compose up -d
```

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080) 即可使用。镜像提供 Linux AMD64 和 ARM64 版本。

如果拉取镜像时提示无权访问，请检查镜像包的可见性，私有镜像需要先通过 `docker login ghcr.io` 登录有读取权限的账户。如果镜像尚未构建完成，可查看仓库的 Actions 页面，或使用下方的源码构建方式。

### 局域网访问和端口设置

默认仅允许部署机器本机访问。需要在局域网内使用时，启动前设置监听地址和端口。

```sh
export OFD_BIND=0.0.0.0
export OFD_PORT=8080
docker compose up -d
```

其他设备访问 `http://服务器IP:8080`。可将 `OFD_PORT` 改为其他未占用的端口。该应用没有登录功能，请将访问范围限制在可信网络，公网部署时应通过反向代理配置 HTTPS 和访问控制。

### 更新、日志和停止

以下命令均在仓库目录执行。每次打开新的终端时，重新设置需要的监听地址和端口。Compose 固定使用 `ghcr.io/wangyanzu/ofd-to-pdf:latest`，每次启动都会检查并拉取最新镜像。运行中的容器需要再次执行启动命令才能更新。

```sh

# 更新镜像并重新启动
docker compose pull
docker compose up -d

# 查看运行状态和日志
docker compose ps
docker compose logs --tail=100 -f

# 停止并移除容器
docker compose down
```

## 从源码构建

克隆仓库后，在仓库目录执行。

```sh
docker build -t ofd-to-pdf:local .
docker run -d --name ofd-to-pdf-local -p 127.0.0.1:8080:8080 ofd-to-pdf:local
```

启动后访问 [http://127.0.0.1:8080](http://127.0.0.1:8080)。

## 不使用 Docker 运行

需要 Python 3.9+、Java 11+，以及能够显示文档中文字形的中文字体。项目已附带 Java 转换引擎及依赖。

```sh
git clone https://github.com/wangyanzu/ofd-to-pdf.git
cd ofd-to-pdf
python3 server.py
```

默认使用 8080 端口。可通过 `PORT` 更改端口，通过 `OFD_JAVA` 指定 Java 可执行文件的路径。

## 转换效果与常见问题

转换优先保留文字和矢量图形，遇到不兼容内容会自动尝试 300 DPI 图片式 PDF。图片式 PDF 的文字无法直接复制或搜索。字体替代可能造成字形差异，转换完成后请核对文字、页面和印章。

可见印章随页面转换，原 OFD 数字签名不会迁移为 PDF 数字签名。请保留原始 OFD 用于验签和归档。

如果转换失败，页面会显示具体原因。文件损坏、缺少可用字体、不支持的印章格式或内容校验失败都可能导致转换终止。可先确认源文件能够正常打开，并结合 `docker compose logs --tail=100` 中的错误诊断。日志可能包含引擎报告的文档片段，分享日志前请检查其中的敏感内容。

如果等待时间过长，默认转换超时为 300 秒，浏览器等待上限为 360 秒。使用反向代理时，需让代理支持至少 50 MB 的请求体和 360 秒的响应等待时间。

## 第三方组件

转换使用 OFDRW 与 PDFBox。第三方组件的许可证及声明随镜像附带，仓库中可查看 [THIRD_PARTY_NOTICES.md](vendor/ofd/engine/dist/THIRD_PARTY_NOTICES.md)。
