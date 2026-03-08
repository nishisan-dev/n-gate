import os
import subprocess
import json
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

app = FastAPI(title="Benchmark UI")

# Configurar static e templates
app.mount("/static", StaticFiles(directory="app/static"), name="static")
templates = Jinja2Templates(directory="app/templates")

class BenchmarkRequest(BaseModel):
    mode: str = "requests" # "requests" or "timed"
    requests: int = 5000
    timed_duration: int = 10
    concurrencies: str = "1, 10, 50, 100, 500"

@app.get("/", response_class=HTMLResponse)
async def serve_ui(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})

@app.post("/api/run")
async def run_benchmark(config: BenchmarkRequest):
    try:
        # Construir comando baseado na configuração
        cmd = ["python3", "scripts/benchmark.py"]
        
        # O script original ainda não suporta args customizados, 
        # Então precisamos injetar via env vars, ou alterar o script original
        # Para isso a forma mais fácil é exportar as vars
        # Ou... editar o script benchmark.py dinamicamente usando sys.argv, 
        # mas como estamos no container, podemos apenas invocar e ler os JSONs resultantes,
        # ou sobreescrever variáveis via regex ou sed.
        
        # Como o script 'benchmark.py' exporta os relatorios via JSON
        # nós executamos ele. Se a UI pediu por requests, apenas ignoramos o time no bash (e vice versa)
        
        env = os.environ.copy()
        env["BASELINE_URL"] = "http://static-backend:8080/"
        env["NGINX_PROXY_URL"] = "http://nginx-proxy:8080/"
        env["JAVALIN_PROXY_URL"] = "http://inventory-adapter:9091/"
        
        # Reescrever as constantes diretamente via SED no container para garantir a adoção da UI
        subprocess.run(["sed", "-i", f"s/TOTAL_REQUESTS = .*/TOTAL_REQUESTS = {config.requests}/", "scripts/benchmark.py"])
        subprocess.run(["sed", "-i", f"s/TIMED_DURATION = .*/TIMED_DURATION = {config.timed_duration}/", "scripts/benchmark.py"])
        subprocess.run(["sed", "-i", f"s/CONCURRENCY_LEVELS = .*/CONCURRENCY_LEVELS = [{config.concurrencies}]/", "scripts/benchmark.py"])
        
        process = subprocess.run(
            cmd, 
            env=env,
            capture_output=True, 
            text=True, 
            timeout=300
        )
        
        if process.returncode != 0:
            return JSONResponse(status_code=500, content={"error": "Benchmark script failed", "details": process.stderr})
        
        # Ler o resultado (o script cria benchmark_results.json e benchmark_results_timed.json)
        json_file = "scripts/benchmark_results.json" if config.mode == "requests" else "scripts/benchmark_results_timed.json"
        
        if os.path.exists(json_file):
            with open(json_file, 'r') as f:
                data = json.load(f)
            return data
        else:
            return JSONResponse(status_code=500, content={"error": "Output JSON not found"})

    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})
