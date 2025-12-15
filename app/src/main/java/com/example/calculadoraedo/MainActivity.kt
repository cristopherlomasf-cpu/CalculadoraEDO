package com.example.calculadoraedo

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var editEdo: EditText
    private lateinit var editIcs: EditText
    private lateinit var textResultado: TextView
    private lateinit var webPreview: WebView
    private lateinit var webLatex: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        editEdo = findViewById(R.id.editEdo)
        editIcs = findViewById(R.id.editIcs)
        textResultado = findViewById(R.id.textResultado)
        webPreview = findViewById(R.id.webPreview)
        webLatex = findViewById(R.id.webLatex)

        webPreview.settings.javaScriptEnabled = true
        webLatex.settings.javaScriptEnabled = true

        editEdo.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                mostrarPreview(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val teclas: List<Pair<Int, String>> = listOf(
            Pair(R.id.btn0, "0"),
            Pair(R.id.btn1, "1"),
            Pair(R.id.btn2, "2"),
            Pair(R.id.btn3, "3"),
            Pair(R.id.btn4, "4"),
            Pair(R.id.btn5, "5"),
            Pair(R.id.btn6, "6"),
            Pair(R.id.btn7, "7"),
            Pair(R.id.btn8, "8"),
            Pair(R.id.btn9, "9"),
            Pair(R.id.btnDot, "."),
            Pair(R.id.btnX, "x"),
            Pair(R.id.btnY, "y"),

            Pair(R.id.btnPlus, "+"),
            Pair(R.id.btnDiv, "/"),
            Pair(R.id.btnEqual, "="),

            Pair(R.id.btnLPar, "("),
            Pair(R.id.btnRPar, ")"),

            Pair(R.id.btnYprime, "y'"),
            Pair(R.id.btnYsec, "y''"),

            Pair(R.id.btnPow, "^"),
            Pair(R.id.btnDx, "dx"),
            Pair(R.id.btnDy, "dy")
        )

        for ((id, txt) in teclas) {
            findViewById<Button>(id).setOnClickListener { insertAtCursor(txt) }
        }

        // Internamente usa ASCII para evitar Unicode raro
        findViewById<Button>(R.id.btnMinus).setOnClickListener { insertAtCursor("-") }
        findViewById<Button>(R.id.btnTimes).setOnClickListener { insertAtCursor("*") }

        // Extras LaTeX (preview)
        findViewById<Button>(R.id.btnFrac).setOnClickListener { insertAtCursor("\\frac{}{}") }
        findViewById<Button>(R.id.btnSup).setOnClickListener { insertAtCursor("^{}") }
        findViewById<Button>(R.id.btnSub).setOnClickListener { insertAtCursor("_{}") }
        findViewById<Button>(R.id.btnSqrt).setOnClickListener { insertAtCursor("\\sqrt{}") }
        findViewById<Button>(R.id.btnInt).setOnClickListener { insertAtCursor("\\int ") }
        findViewById<Button>(R.id.btnSum).setOnClickListener { insertAtCursor("\\sum ") }

        findViewById<Button>(R.id.btnAlpha).setOnClickListener { insertAtCursor("\\alpha ") }
        findViewById<Button>(R.id.btnBeta).setOnClickListener { insertAtCursor("\\beta ") }
        findViewById<Button>(R.id.btnPi).setOnClickListener { insertAtCursor("\\pi ") }

        findViewById<Button>(R.id.btnDel).setOnClickListener { backspace() }
        findViewById<Button>(R.id.btnResolver).setOnClickListener { resolverEcuacion() }

        mostrarPreview("")
    }

    private fun insertAtCursor(fragment: String) {
        val start = editEdo.selectionStart.coerceAtLeast(0)
        val end = editEdo.selectionEnd.coerceAtLeast(0)
        val a = min(start, end)
        val b = max(start, end)

        editEdo.text.replace(a, b, fragment)
        editEdo.setSelection(a + fragment.length)
    }

    private fun backspace() {
        val start = editEdo.selectionStart.coerceAtLeast(0)
        val end = editEdo.selectionEnd.coerceAtLeast(0)
        val a = min(start, end)
        val b = max(start, end)

        if (a != b) {
            editEdo.text.delete(a, b)
            editEdo.setSelection(a)
            return
        }
        if (a > 0) {
            editEdo.text.delete(a - 1, a)
            editEdo.setSelection(a - 1)
        }
    }

    private fun aFormatoParaPython(expr: String): String {
        var s = expr.trim()

        // --- Normalización de caracteres raros (tu código) ---
        s = s.replace("′", "'").replace("’", "'").replace("‵", "'")
        s = s.replace("−", "-").replace("–", "-").replace("—", "-")
        s = s.replace("·", "*").replace("⋅", "*").replace("×", "*")
        s = s.replace("\\\\cdot".toRegex(), "*")          // si llega \cdot del preview
        s = s.replace("\\s*=\\s*".toRegex(), "=")
        s = s.replace("\\s+".toRegex(), " ").trim()

        // --- Reglas SymPy ---
        // Potencias: ^ -> **
        s = s.replace("^", "**")

        // Derivadas del teclado
        s = s.replace("y''", "diff(y(x),x,2)")
        s = s.replace("y'", "diff(y(x),x)")

        // Si el usuario escribe ecuación diferencial tipo: A dx + B dy = 0
        // la convertimos a: A + B*y' = 0  (donde y' = diff(y(x),x))
        if (s.contains("dx") || s.contains("dy")) {
            // Primero reemplazar dy
            s = s.replace("dy", "*diff(y(x),x)")
            // Luego eliminar dx
            s = s.replace("dx", "")
            s = s.replace("\\s+".toRegex(), " ").trim()
        }

        // Convertir y -> y(x) (después de y' y y'')
        // Para no duplicar y(x), protegemos temporalmente.
        s = s.replace("y(x)", "YFUNC_TMP")
        s = s.replace("y", "y(x)")
        s = s.replace("YFUNC_TMP", "y(x)")

        // Multiplicación implícita mínima:
        // 2x -> 2*x
        s = s.replace("(\\d)(x)".toRegex(), "$1*$2")
        // 3y(x) -> 3*y(x)
        s = s.replace("(\\d)(y\\(x\\))".toRegex(), "$1*$2")
        // xy(x) -> x*y(x)  y también x y(x) -> x*y(x)
        s = s.replace("xy\\(x\\)".toRegex(), "x*y(x)")
        s = s.replace("x\\s*y\\(x\\)".toRegex(), "x*y(x)")

        // Limpieza final
        s = s.replace("\\s+".toRegex(), " ").trim()

        return s
    }


    private fun resolverEcuacion() {
        // (Opcional) desactivar botón mientras resuelve
        val btn = findViewById<Button>(R.id.btnResolver)
        btn.isEnabled = false
        textResultado.text = "Resolviendo..."

        val edoUsuario = editEdo.text.toString()
        val edoParaPython = aFormatoParaPython(edoUsuario)
        val icsStr = editIcs.text.toString()

        Thread {
            try {
                val py = Python.getInstance()
                val modulo: PyObject = py.getModule("solver")
                val resultado: PyObject = modulo.callAttr("resolver_edo", edoParaPython, icsStr)

                val lista = resultado.asList()
                val texto = lista[0].toString()
                val latexSol = lista[1].toString()

                runOnUiThread {
                    textResultado.text = texto
                    mostrarLatexSolucion(latexSol)
                    btn.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    textResultado.text = "ERROR al resolver:\n${e.message}"
                    mostrarLatexSolucion("ERROR al resolver")
                    btn.isEnabled = true
                }
            }
        }.start()
    }


    private fun mostrarPreview(latexOrText: String) {
        val safe = latexOrText.replace("\n", " ")
        val html = """
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
              </head>
              <body>
                <div style="font-size: 130%; padding: 8px; text-align: center;">
                  \[ $safe \]
                </div>
              </body>
            </html>
        """.trimIndent()
        webPreview.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun mostrarLatexSolucion(latexSol: String) {
        val safeSol = latexSol.replace("\n", " ")
        val html = """
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
              </head>
              <body>
                <div style="font-size: 130%; padding: 8px; text-align: center;">
                  \[ $safeSol \]
                </div>
              </body>
            </html>
        """.trimIndent()
        webLatex.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
