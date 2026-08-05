package io.github.yearsyan.ohpi.syntax

/** Built-in language table: keyword sets plus comment/string conventions. */
object Languages {

    private val kotlin = LanguageSpec(
        name = "kotlin",
        extensions = listOf("kt", "kts"),
        keywords = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var",
            "if", "else", "when", "for", "while", "do", "return", "break", "continue",
            "in", "is", "as", "try", "catch", "finally", "throw", "this", "super",
            "null", "true", "false", "data", "sealed", "enum", "companion", "init",
            "constructor", "by", "lazy", "vararg", "suspend", "override", "private",
            "public", "internal", "protected", "open", "abstract", "final", "lateinit",
            "const", "inline", "reified", "out", "typealias", "where", "get", "set",
            "actual", "expect",
        ),
        annotationChar = '@',
    )

    private val java = LanguageSpec(
        name = "java",
        extensions = listOf("java"),
        keywords = setOf(
            "package", "import", "class", "interface", "enum", "record", "extends",
            "implements", "new", "if", "else", "switch", "case", "default", "for",
            "while", "do", "return", "break", "continue", "try", "catch", "finally",
            "throw", "throws", "this", "super", "null", "true", "false", "void",
            "int", "long", "double", "float", "boolean", "char", "byte", "short",
            "static", "final", "abstract", "synchronized", "volatile", "transient",
            "native", "strictfp", "public", "private", "protected", "instanceof",
            "var", "sealed", "permits", "yield",
        ),
        annotationChar = '@',
    )

    private val go = LanguageSpec(
        name = "go",
        extensions = listOf("go"),
        keywords = setOf(
            "package", "import", "func", "var", "const", "type", "struct", "interface",
            "map", "chan", "go", "defer", "select", "switch", "case", "default",
            "for", "range", "if", "else", "return", "break", "continue", "fallthrough",
            "goto", "iota", "nil", "true", "false", "make", "new", "append", "len", "cap",
        ),
        langAliases = listOf("golang"),
    )

    private val python = LanguageSpec(
        name = "python",
        extensions = listOf("py", "pyw"),
        keywords = setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "in",
            "not", "and", "or", "is", "None", "True", "False", "import", "from",
            "as", "with", "lambda", "try", "except", "finally", "raise", "pass",
            "break", "continue", "global", "nonlocal", "yield", "async", "await",
            "del", "assert", "match", "case",
        ),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        annotationChar = '@',
        langAliases = listOf("py"),
    )

    private val javascript = LanguageSpec(
        name = "javascript",
        extensions = listOf("js", "jsx", "mjs", "cjs"),
        keywords = setOf(
            "function", "var", "let", "const", "if", "else", "switch", "case",
            "default", "for", "while", "do", "return", "break", "continue", "new",
            "delete", "typeof", "instanceof", "in", "of", "try", "catch", "finally",
            "throw", "this", "super", "null", "undefined", "true", "false", "class",
            "extends", "static", "get", "set", "async", "await", "yield", "import",
            "export", "from", "void",
        ),
        langAliases = listOf("js"),
    )

    private val typescript = javascript.copy(
        name = "typescript",
        extensions = listOf("ts", "tsx", "mts", "cts"),
        keywords = javascript.keywords + setOf(
            "interface", "type", "implements", "readonly", "declare", "namespace",
            "abstract", "enum", "keyof", "infer", "never", "unknown", "any",
            "string", "number", "boolean", "public", "private", "protected",
        ),
        langAliases = listOf("ts"),
    )

    private val rust = LanguageSpec(
        name = "rust",
        extensions = listOf("rs"),
        keywords = setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait",
            "for", "while", "loop", "if", "else", "match", "return", "break",
            "continue", "in", "use", "mod", "pub", "crate", "self", "Self", "super",
            "where", "async", "await", "move", "ref", "dyn", "unsafe", "extern",
            "type", "as", "true", "false",
        ),
        langAliases = listOf("rs"),
    )

    private val cKeywords = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do",
        "double", "else", "enum", "extern", "float", "for", "goto", "if", "int",
        "long", "register", "return", "short", "signed", "sizeof", "static",
        "struct", "switch", "typedef", "union", "unsigned", "void", "volatile",
        "while", "bool", "true", "false", "inline", "restrict",
    )

    // '#' marks preprocessor directives, highlighted like annotations.
    private val c = LanguageSpec(
        name = "c",
        extensions = listOf("c", "h"),
        keywords = cKeywords,
        annotationChar = '#',
    )

    private val cpp = LanguageSpec(
        name = "cpp",
        extensions = listOf("cpp", "cc", "cxx", "hpp", "hh"),
        keywords = cKeywords + setOf(
            "class", "namespace", "template", "typename", "this", "new", "delete",
            "try", "catch", "throw", "using", "virtual", "override", "final",
            "public", "private", "protected", "friend", "operator", "mutable",
            "explicit", "constexpr", "noexcept", "nullptr", "static_cast",
            "dynamic_cast", "reinterpret_cast", "const_cast", "concept", "requires",
            "decltype", "typeid",
        ),
        annotationChar = '#',
        langAliases = listOf("c++"),
    )

    private val json = LanguageSpec(
        name = "json",
        extensions = listOf("json"),
        keywords = setOf("true", "false", "null"),
        lineComments = emptyList(),
        blockComments = emptyList(),
        stringQuotes = setOf('"'),
    )

    private val shell = LanguageSpec(
        name = "shell",
        extensions = listOf("sh", "bash", "zsh"),
        keywords = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do",
            "done", "case", "esac", "in", "function", "select", "return", "break",
            "continue", "local", "export", "readonly", "declare", "shift", "source",
            "set", "unset", "trap", "eval", "exec",
        ),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        langAliases = listOf("bash", "zsh"),
    )

    private val sql = LanguageSpec(
        name = "sql",
        extensions = listOf("sql"),
        keywords = setOf(
            "select", "from", "where", "insert", "update", "delete", "create",
            "drop", "alter", "table", "index", "view", "join", "inner", "left",
            "right", "full", "outer", "on", "group", "by", "order", "having",
            "limit", "offset", "union", "all", "distinct", "as", "and", "or",
            "not", "null", "in", "exists", "between", "like", "is", "case",
            "when", "then", "else", "end", "into", "values", "set", "primary",
            "key", "foreign", "references", "unique", "check", "default",
            "constraint", "begin", "commit", "rollback", "transaction", "desc", "asc",
        ),
        caseInsensitiveKeywords = true,
        lineComments = listOf("--"),
    )

    private val yaml = LanguageSpec(
        name = "yaml",
        extensions = listOf("yaml", "yml"),
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        stringQuotes = setOf('"', '\''),
        langAliases = listOf("yml"),
    )

    private val toml = LanguageSpec(
        name = "toml",
        extensions = listOf("toml"),
        keywords = setOf("true", "false"),
        lineComments = listOf("#"),
        blockComments = emptyList(),
        stringQuotes = setOf('"', '\''),
    )

    private val xml = LanguageSpec(
        name = "xml",
        extensions = listOf("xml", "html", "htm", "svg"),
        keywords = emptySet(),
        markup = true,
        langAliases = listOf("html"),
    )

    private val css = LanguageSpec(
        name = "css",
        extensions = listOf("css"),
        keywords = setOf("important", "media", "supports", "keyframes", "font-face"),
        stringQuotes = setOf('"', '\''),
        annotationChar = '@',
    )

    val all: List<LanguageSpec> = listOf(
        kotlin, java, go, python, javascript, typescript, rust, c, cpp,
        json, shell, sql, yaml, toml, xml, css,
    )
}
