import os
import asyncio
import json
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sse_starlette.sse import EventSourceResponse

app = FastAPI(title="Benchmark UI")

# Configurar static e templates
app.mount("/static", StaticFiles(directory="app/static"), name="static")
templates = Jinja2Templates(directory="app/templates")


@app.get("/", response_class=HTMLResponse)
async def serve_ui(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})


@app.get("/api/run-stream")
async def run_benchmark_stream(request: Request, mode: str = "requests", requests: int = 5000,
                                timed_duration: int = 10, concurrencies: str = "1,10,50,100,500"):
    """Executa o benchmark e envia progresso via SSE."""

    async def event_generator():
        env = os.environ.copy()
        env["BASELINE_URL"] = "http://static-backend:8080/"
        env["NGINX_PROXY_URL"] = "http://nginx-proxy:8080/"
        env["JAVALIN_PROXY_URL"] = "http://inventory-adapter:9091/"
        env["BENCHMARK_MODE"] = mode

        # Modificar constantes no script via sed
        sed_cmds = [
            ["sed", "-i", f"s/TOTAL_REQUESTS = .*/TOTAL_REQUESTS = {requests}/", "scripts/benchmark.py"],
            ["sed", "-i", f"s/TIMED_DURATION = .*/TIMED_DURATION = {timed_duration}/", "scripts/benchmark.py"],
            ["sed", "-i", f"s/CONCURRENCY_LEVELS = .*/CONCURRENCY_LEVELS = [{concurrencies}]/", "scripts/benchmark.py"],
        ]
        for cmd in sed_cmds:
            proc = await asyncio.create_subprocess_exec(*cmd)
            await proc.wait()

        # Executar benchmark de forma async, lendo stdout line-by-line
        process = await asyncio.create_subprocess_exec(
            "python3", "scripts/benchmark.py",
            env=env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        try:
            while True:
                # Verificar se o cliente desconectou
                if await request.is_disconnected():
                    process.kill()
                    break

                line = await asyncio.wait_for(process.stdout.readline(), timeout=300)
                if not line:
                    break

                decoded = line.decode("utf-8").strip()
                if decoded.startswith("PROGRESS:"):
                    json_str = decoded[len("PROGRESS:"):]
                    yield {"event": "progress", "data": json_str}

            await process.wait()

            # Ler o JSON final de resultados
            json_file = "scripts/benchmark_results.json" if mode == "requests" else "scripts/benchmark_results_timed.json"
            if mode == "all":
                # Se modo all, enviar ambos
                for suffix, evt_name in [("", "result_requests"), ("_timed", "result_timed")]:
                    path = f"scripts/benchmark_results{suffix}.json"
                    if os.path.exists(path):
                        with open(path, "r") as f:
                            data = json.load(f)
                        yield {"event": evt_name, "data": json.dumps(data)}
            else:
                if os.path.exists(json_file):
                    with open(json_file, "r") as f:
                        data = json.load(f)
                    yield {"event": "result", "data": json.dumps(data)}
                else:
                    yield {"event": "error", "data": json.dumps({"error": "Output JSON not found"})}

        except asyncio.TimeoutError:
            process.kill()
            yield {"event": "error", "data": json.dumps({"error": "Benchmark timeout (300s)"})}
        except Exception as e:
            process.kill()
            yield {"event": "error", "data": json.dumps({"error": str(e)})}

    return EventSourceResponse(event_generator())
