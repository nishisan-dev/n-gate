#!/usr/bin/env python3
"""
Inventory Adapter — Benchmark & Overhead Report
Usa Apache Bench (ab) para medir:
  - Baseline: nginx estático direto (porta 3080)
  - Proxy:    adapter sem auth (porta 9091)
Calcula o overhead do adapter por comparação.
"""

import subprocess
import re
import sys
import json
from datetime import datetime

# ─── Configuração ────────────────────────────────────────────────────────────

BASELINE_URL = "http://localhost:3080/"
PROXY_URL = "http://localhost:9091/"

TOTAL_REQUESTS = 5000
CONCURRENCY_LEVELS = [1, 10, 50, 100, 500]
WARMUP_REQUESTS = 50
TIMED_DURATION = 10  # segundos por nível de concorrência

# ─── Parser do output do ab ──────────────────────────────────────────────────

def run_ab(url, requests, concurrency):
    """Executa ab por contagem de requests."""
    cmd = ["ab", "-n", str(requests), "-c", str(concurrency), "-q", url]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        print(f"  ⚠ ab falhou: {result.stderr.strip()}")
        return None
    return result.stdout


def run_ab_timed(url, duration, concurrency):
    """Executa ab por tempo (segundos). Usa -n alto como safety net."""
    cmd = ["ab", "-t", str(duration), "-n", "999999", "-c", str(concurrency), "-q", url]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=duration + 30)
    if result.returncode != 0:
        print(f"  ⚠ ab falhou: {result.stderr.strip()}")
        return None
    return result.stdout


def parse_ab_output(output):
    """Extrai métricas relevantes do output do ab."""
    if output is None:
        return None

    metrics = {}

    patterns = {
        "rps": r"Requests per second:\s+([\d.]+)",
        "time_per_request": r"Time per request:\s+([\d.]+)\s+\[ms\]\s+\(mean\)",
        "time_per_request_concurrent": r"Time per request:\s+([\d.]+)\s+\[ms\]\s+\(mean, across all concurrent requests\)",
        "transfer_rate": r"Transfer rate:\s+([\d.]+)",
        "total_time": r"Time taken for tests:\s+([\d.]+)\s+seconds",
        "complete_requests": r"Complete requests:\s+(\d+)",
        "failed_requests": r"Failed requests:\s+(\d+)",
        "p50": r"50%\s+(\d+)",
        "p90": r"90%\s+(\d+)",
        "p95": r"95%\s+(\d+)",
        "p99": r"99%\s+(\d+)",
        "p100": r"100%\s+(\d+)",
    }

    for key, pattern in patterns.items():
        match = re.search(pattern, output)
        if match:
            metrics[key] = float(match.group(1))

    return metrics


# ─── Relatório ───────────────────────────────────────────────────────────────

def format_bar(value, max_value, width=30):
    """Cria uma barra visual proporcional."""
    if max_value == 0:
        return ""
    filled = int((value / max_value) * width)
    return "█" * filled + "░" * (width - filled)


def print_report(results, mode="requests"):
    """Imprime o relatório comparativo formatado."""
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print()
    print("=" * 78)
    print(f"  INVENTORY ADAPTER — BENCHMARK REPORT")
    print(f"  {now}")
    print("=" * 78)
    print()
    print(f"  Baseline : {BASELINE_URL} (nginx direto)")
    print(f"  Proxy    : {PROXY_URL} (adapter → nginx)")
    print(f"  Mode     : {mode}")
    if mode == 'requests':
        print(f"  Requests : {TOTAL_REQUESTS} por cenário")
    else:
        print(f"  Duração  : {TIMED_DURATION}s por cenário")
    print()

    for concurrency in CONCURRENCY_LEVELS:
        baseline = results.get(("baseline", concurrency))
        proxy = results.get(("proxy", concurrency))

        if not baseline or not proxy:
            print(f"  ⚠ Dados insuficientes para concorrência {concurrency}")
            continue

        overhead_ms = proxy["time_per_request"] - baseline["time_per_request"]
        overhead_pct = (overhead_ms / baseline["time_per_request"]) * 100 if baseline["time_per_request"] > 0 else 0

        print("─" * 78)
        print(f"  CONCORRÊNCIA: {concurrency}")
        print("─" * 78)
        print()

        # Tabela principal
        header = f"  {'Métrica':<35} {'Baseline':>12} {'Proxy':>12} {'Overhead':>12}"
        print(header)
        print(f"  {'─' * 71}")

        rows = [
            ("Req/s", "rps", "{:.1f}", False),
            ("Tempo médio (ms)", "time_per_request", "{:.2f}", True),
            ("p50 (ms)", "p50", "{:.0f}", True),
            ("p90 (ms)", "p90", "{:.0f}", True),
            ("p95 (ms)", "p95", "{:.0f}", True),
            ("p99 (ms)", "p99", "{:.0f}", True),
            ("p100 / max (ms)", "p100", "{:.0f}", True),
            ("Falhas", "failed_requests", "{:.0f}", False),
        ]

        for label, key, fmt, show_overhead in rows:
            b_val = baseline.get(key, 0)
            p_val = proxy.get(key, 0)

            b_str = fmt.format(b_val)
            p_str = fmt.format(p_val)

            if show_overhead and b_val > 0:
                diff = p_val - b_val
                sign = "+" if diff >= 0 else ""
                o_str = f"{sign}{fmt.format(diff)}"
            elif key == "rps" and b_val > 0:
                diff_pct = ((p_val - b_val) / b_val) * 100
                o_str = f"{diff_pct:+.1f}%"
            else:
                o_str = "—"

            print(f"  {label:<35} {b_str:>12} {p_str:>12} {o_str:>12}")

        print()

        # Barras visuais
        max_latency = max(proxy["time_per_request"], baseline["time_per_request"])
        print(f"  Latência média:")
        print(f"    Baseline {format_bar(baseline['time_per_request'], max_latency)} {baseline['time_per_request']:.2f}ms")
        print(f"    Proxy    {format_bar(proxy['time_per_request'], max_latency)} {proxy['time_per_request']:.2f}ms")
        print()
        print(f"  ➜ Overhead do adapter: {overhead_ms:.2f}ms ({overhead_pct:.1f}%)")
        print()

    # Resumo final
    print("=" * 78)
    print("  RESUMO")
    print("=" * 78)
    print()
    for concurrency in CONCURRENCY_LEVELS:
        baseline = results.get(("baseline", concurrency))
        proxy = results.get(("proxy", concurrency))
        if baseline and proxy:
            overhead = proxy["time_per_request"] - baseline["time_per_request"]
            print(f"  c={concurrency:<4}  overhead médio: {overhead:.2f}ms  "
                  f"(baseline: {baseline['time_per_request']:.2f}ms → proxy: {proxy['time_per_request']:.2f}ms)")
    print()
    print("=" * 78)

    # Salvar JSON
    json_data = {
        "timestamp": now,
        "mode": mode,
        "config": {
            "baseline_url": BASELINE_URL,
            "proxy_url": PROXY_URL,
            "total_requests": TOTAL_REQUESTS if mode == "requests" else None,
            "timed_duration": TIMED_DURATION if mode == "timed" else None,
            "concurrency_levels": CONCURRENCY_LEVELS,
        },
        "results": {}
    }
    for (target, concurrency), metrics in results.items():
        key = f"{target}_c{concurrency}"
        json_data["results"][key] = metrics

    suffix = "_timed" if mode == "timed" else ""
    json_path = f"scripts/benchmark_results{suffix}.json"
    with open(json_path, "w") as f:
        json.dump(json_data, f, indent=2)
    print(f"\n  Resultados salvos em: {json_path}")
    print()


# ─── Main ────────────────────────────────────────────────────────────────────

def main():
    # Verificar ab
    try:
        subprocess.run(["ab", "-V"], capture_output=True, check=True)
    except FileNotFoundError:
        print("❌ Apache Bench (ab) não encontrado. Instale: sudo apt install apache2-utils")
        sys.exit(1)

    # Verificar conectividade
    print("\n🔍 Verificando conectividade...")
    for name, url in [("Baseline (nginx)", BASELINE_URL), ("Proxy (adapter)", PROXY_URL)]:
        try:
            result = subprocess.run(["curl", "-sf", "-o", "/dev/null", "-w", "%{http_code}", url],
                                    capture_output=True, text=True, timeout=5)
            code = result.stdout.strip()
            if code == "200":
                print(f"  ✅ {name}: OK")
            else:
                print(f"  ❌ {name}: HTTP {code}")
                sys.exit(1)
        except Exception as e:
            print(f"  ❌ {name}: {e}")
            sys.exit(1)

    results = {}

    # Warmup
    print(f"\n🔥 Warmup ({WARMUP_REQUESTS} requests em cada endpoint)...")
    run_ab(BASELINE_URL, WARMUP_REQUESTS, 1)
    run_ab(PROXY_URL, WARMUP_REQUESTS, 1)
    print("  ✅ Warmup concluído")

    # ── Fase 1: Benchmark por contagem de requests ────────────────────────
    print(f"\n{'='*78}")
    print(f"  FASE 1: BENCHMARK POR REQUESTS ({TOTAL_REQUESTS} requests)")
    print(f"{'='*78}")

    for concurrency in CONCURRENCY_LEVELS:
        print(f"\n📊 Benchmark com concorrência={concurrency}, requests={TOTAL_REQUESTS}")

        print(f"  → Baseline (nginx direto)...")
        output = run_ab(BASELINE_URL, TOTAL_REQUESTS, concurrency)
        metrics = parse_ab_output(output)
        if metrics:
            results[("baseline", concurrency)] = metrics
            print(f"    {metrics.get('rps', 0):.1f} req/s, {metrics.get('time_per_request', 0):.2f}ms avg")

        print(f"  → Proxy (adapter)...")
        output = run_ab(PROXY_URL, TOTAL_REQUESTS, concurrency)
        metrics = parse_ab_output(output)
        if metrics:
            results[("proxy", concurrency)] = metrics
            print(f"    {metrics.get('rps', 0):.1f} req/s, {metrics.get('time_per_request', 0):.2f}ms avg")

    print_report(results, mode="requests")

    # ── Fase 2: Benchmark por tempo ──────────────────────────────────────
    print(f"\n{'='*78}")
    print(f"  FASE 2: BENCHMARK POR TEMPO ({TIMED_DURATION}s por cenário)")
    print(f"{'='*78}")

    timed_results = {}

    for concurrency in CONCURRENCY_LEVELS:
        print(f"\n⏱  Benchmark com concorrência={concurrency}, duração={TIMED_DURATION}s")

        print(f"  → Baseline (nginx direto)...")
        output = run_ab_timed(BASELINE_URL, TIMED_DURATION, concurrency)
        metrics = parse_ab_output(output)
        if metrics:
            timed_results[("baseline", concurrency)] = metrics
            total = int(metrics.get('complete_requests', 0))
            print(f"    {metrics.get('rps', 0):.1f} req/s, {metrics.get('time_per_request', 0):.2f}ms avg, {total} total reqs")

        print(f"  → Proxy (adapter)...")
        output = run_ab_timed(PROXY_URL, TIMED_DURATION, concurrency)
        metrics = parse_ab_output(output)
        if metrics:
            timed_results[("proxy", concurrency)] = metrics
            total = int(metrics.get('complete_requests', 0))
            print(f"    {metrics.get('rps', 0):.1f} req/s, {metrics.get('time_per_request', 0):.2f}ms avg, {total} total reqs")

    print_report(timed_results, mode="timed")


if __name__ == "__main__":
    main()
