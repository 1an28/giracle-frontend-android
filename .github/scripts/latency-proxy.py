#!/usr/bin/env python3
"""遅い回線を作る TCP の中継 (D-046)。

使い方: latency-proxy.py <待ち受けるポート> <転送先のホスト> <転送先のポート> <片道の遅延 ms>

受けた通信を、向きごとに決まった時間だけ遅らせてから転送する (帯域は絞らない)。
往復では遅延の 2 倍かかる。e2e で「よく使う作業」を遅い回線で測るときに、端末とサーバーの間に置く。
エミュレータの `adb emu network delay` は効いたり効かなかったりした (#57) ので、ランナーの上で確実に遅らせる。
"""
import asyncio
import sys
import time


async def pipe(reader, writer, delay):
    """reader から読んだものを、受けた時刻 + delay になってから writer へ書く (順番は保つ)"""
    queue = asyncio.Queue()

    async def pump_in():
        try:
            while True:
                data = await reader.read(65536)
                await queue.put((time.monotonic() + delay, data))
                if not data:
                    return
        except Exception:
            await queue.put((time.monotonic() + delay, b""))

    async def pump_out():
        try:
            while True:
                due, data = await queue.get()
                wait = due - time.monotonic()
                if wait > 0:
                    await asyncio.sleep(wait)
                if not data:
                    break
                writer.write(data)
                await writer.drain()
        except Exception:
            pass
        finally:
            try:
                writer.close()
            except Exception:
                pass

    await asyncio.gather(pump_in(), pump_out())


async def main():
    listen_port, host, port, delay_ms = int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    delay = delay_ms / 1000

    async def handle(client_reader, client_writer):
        try:
            server_reader, server_writer = await asyncio.open_connection(host, port)
        except Exception:
            client_writer.close()
            return
        await asyncio.gather(pipe(client_reader, server_writer, delay), pipe(server_reader, client_writer, delay))

    server = await asyncio.start_server(handle, "127.0.0.1", listen_port)
    print(f"latency-proxy: 127.0.0.1:{listen_port} -> {host}:{port} (片道 {delay_ms}ms)", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
