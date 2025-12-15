from sympy import symbols, Function, Eq, sympify, dsolve, latex, diff
from sympy.solvers.ode import classify_ode
from sympy.parsing.latex import parse_latex
import re
import signal
from contextlib import contextmanager

x = symbols('x')
y = Function('y')
LOCAL_DICT = {"x": x, "y": y}


# ---------- Timeout ----------
class TimeoutError(Exception):
    pass

@contextmanager
def time_limit(seconds: int):
    def handler(signum, frame):
        raise TimeoutError(f"Tiempo excedido ({seconds}s)")

    old = signal.signal(signal.SIGALRM, handler)
    try:
        signal.alarm(max(1, int(seconds)))
        yield
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, old)


# ---------- Parse helpers ----------
def _es_latex(cadena: str) -> bool:
    return "\\" in cadena

def _pre_normalizar_ascii(s: str) -> str:
    s = s.replace("′", "'").replace("’", "'").replace("‵", "'")
    s = s.replace("−", "-").replace("–", "-").replace("—", "-")
    s = s.replace("·", "*").replace("⋅", "*").replace("×", "*")
    return s

def _insertar_mult_implicita_para_dxdy(s: str) -> str:
    t = s.replace(" ", "")

    # x2, y2 -> x**2, y**2
    t = re.sub(r"\bx(\d+)\b", r"x**\1", t)
    t = re.sub(r"\by(\d+)\b", r"y**\1", t)

    # 2x, 2y -> 2*x, 2*y
    t = re.sub(r"(\d)([xy])\b", r"\1*\2", t)

    # xy, yx -> x*y, y*x
    t = re.sub(r"\bx\s*y\b", "x*y", t)
    t = re.sub(r"\by\s*x\b", "y*x", t)
    t = re.sub(r"\bxy\b", "x*y", t)
    t = re.sub(r"\byx\b", "y*x", t)

    # (...)x o (...)y -> (...)*x / (...)*y
    t = re.sub(r"\)([xy])\b", r")*\1", t)

    # (...)dx o (...)dy -> (...)*dx / (...)*dy
    t = re.sub(r"\)(d[xy])\b", r")*\1", t)

    # xdx, ydy, xdy, ydx -> x*dx etc.
    t = re.sub(r"\b([xy])(d[xy])\b", r"\1*\2", t)

    return t

def _normalizar_primas_sympy(cadena: str) -> str:
    def reemplazo_primas(match: re.Match) -> str:
        primes = match.group(1)
        n = len(primes)
        if n == 1:
            return "diff(y(x),x)"
        return f"diff(y(x),x,{n})"

    s = _pre_normalizar_ascii(cadena)
    s = _insertar_mult_implicita_para_dxdy(s)
    s = re.sub(r"y('+)", reemplazo_primas, s)
    s = re.sub(r"\by\b(?!\s*\()", "y(x)", s)
    return s

def _a_edo_si_es_diferencial(s: str):
    """
    M dx + N dy = 0  ->  y' = -M/N
    """
    t = s.replace(" ", "")

    if "=" in t:
        lhs, rhs = t.split("=", 1)
        if rhs not in ("0", "+0", "-0"):
            return None
        t = lhs

    if "dx" not in t or "dy" not in t:
        return None

    parts = t.split("dy")
    if len(parts) != 2 or parts[1] != "":
        return None

    left = parts[0]
    if "dx" not in left:
        return None

    m_str, n_str = left.split("dx", 1)
    if m_str == "" or n_str == "":
        return None

    if n_str[0] not in "+-":
        n_str = "+" + n_str

    M = sympify(m_str, locals=LOCAL_DICT)
    N = sympify(n_str, locals=LOCAL_DICT)

    return Eq(diff(y(x), x), -M / N)

def _construir_edo(ecuacion_str: str):
    s = _pre_normalizar_ascii(ecuacion_str.strip())

    if _es_latex(s):
        if "=" in s:
            izq_str, der_str = s.split("=", 1)
            izq = parse_latex(izq_str.strip())
            der = parse_latex(der_str.strip())
            return Eq(izq, der)
        expr = parse_latex(s)
        return Eq(expr, 0)

    s = _normalizar_primas_sympy(s)

    edo_diff = _a_edo_si_es_diferencial(s)
    if edo_diff is not None:
        return edo_diff

    if "=" in s:
        izq_str, der_str = s.split("=", 1)
        izq = sympify(izq_str.strip(), locals=LOCAL_DICT)
        der = sympify(der_str.strip(), locals=LOCAL_DICT)
        return Eq(izq, der)

    return Eq(sympify(s, locals=LOCAL_DICT), 0)


# ---------- Solver ----------
def resolver_edo(ecuacion_str, ics_str):
    salida = []
    latex_sol = r""

    # Parse
    try:
        edo = _construir_edo(ecuacion_str)
    except Exception as e:
        return f"ERROR al interpretar la ecuación:\n{e}", r"\text{ERROR al interpretar la ecuación}"

    # Clasificación (para decidir hints)
    hints_limpios = []
    try:
        hints = classify_ode(edo, y(x))
        hints_limpios = [str(h) for h in hints]
    except Exception:
        pass

    # PVI
    ics = None
    if ics_str:
        try:
            x0_str, y0_str = ics_str.split(",", 1)
            x0 = float(x0_str)
            y0 = float(y0_str)
            ics = (x0, y0)
            salida.append(f"Condición inicial: y({x0}) = {y0}")
            salida.append("")
        except Exception:
            salida.append("Advertencia: PVI inválido. Usa formato x0,y0 (ej: 0,1).")
            salida.append("")
            ics = None

    salida.append("=== SOLUCIÓN ===")

    # Orden de intentos: si SymPy dice que es exact, probar exact primero, etc.
    candidatos = []
    for pref in ["exact", "separable", "1st_linear", "Bernoulli", "Cauchy_Euler"]:
        if pref in hints_limpios:
            candidatos.append(pref)
    # siempre intentar soluciones con integrales (suelen salir rápido)
    candidatos += ["all_Integral", "best_hint"]

    # Resolver con timeout por intento
    try:
        sol = None
        ultimo_error = None

        for h in candidatos:
            try:
                with time_limit(8):  # 8s por intento (ajústalo)
                    if ics is None:
                        sol = dsolve(edo, y(x), hint=h)
                    else:
                        x0, y0 = ics
                        sol = dsolve(edo, y(x), ics={y(x0): y0}, hint=h)
                if sol is not None:
                    break
            except TimeoutError as te:
                ultimo_error = te
            except Exception as e:
                ultimo_error = e

        if sol is None:
            salida.append("No se pudo obtener solución simbólica en tiempo razonable.")
            if ultimo_error is not None:
                salida.append(f"Detalle: {ultimo_error}")
            latex_sol = r"\text{No se pudo obtener solución simbólica (timeout)}"
            return "\n".join(salida), latex_sol

        # Formateo
        try:
            rhs = sol.rhs
            salida.append(f"y(x) = {rhs}")
            latex_sol = r"y(x) = " + latex(rhs)
        except Exception:
            salida.append(str(sol))
            latex_sol = latex(sol)

        return "\n".join(salida), latex_sol

    except Exception as e:
        salida.append(f"SymPy no pudo resolver simbólicamente esta EDO:\n{e}")
        latex_sol = r"\text{Error al resolver la EDO}"
        return "\n".join(salida), latex_sol
