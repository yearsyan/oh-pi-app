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
        multilineStringDelimiters = listOf("\"\"\"" to "\"\"\""),
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
        multilineStringDelimiters = listOf("\"\"\"" to "\"\"\""),
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
        multilineStringDelimiters = listOf("`" to "`"),
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
        multilineStringDelimiters = listOf(
            "\"\"\"" to "\"\"\"",
            "'''" to "'''",
        ),
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
        multilineStringDelimiters = listOf("`" to "`"),
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
        // Unlike most programming-language literals, shell quotes may continue
        // across physical lines (common in `python -c` and long tool commands).
        multilineStringDelimiters = listOf("\"" to "\"", "'" to "'"),
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

    private val dart = LanguageSpec(
        name = "dart",
        extensions = listOf("dart"),
        keywords = setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch",
            "class", "const", "continue", "default", "deferred", "do", "dynamic",
            "else", "enum", "export", "extends", "extension", "external", "factory",
            "false", "final", "finally", "for", "function", "get", "hide", "if",
            "implements", "import", "in", "interface", "is", "late", "library",
            "mixin", "new", "null", "on", "operator", "part", "required", "rethrow",
            "return", "sealed", "set", "show", "static", "super", "switch", "sync",
            "this", "throw", "true", "try", "typedef", "var", "void", "when", "while",
            "with", "yield",
        ),
        annotationChar = '@',
    )

    private val csharp = LanguageSpec(
        name = "csharp",
        extensions = listOf("cs", "csx"),
        keywords = setOf(
            "abstract", "as", "async", "await", "base", "bool", "break", "byte",
            "case", "catch", "char", "checked", "class", "const", "continue", "decimal",
            "default", "delegate", "do", "double", "else", "enum", "event", "explicit",
            "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto",
            "if", "implicit", "in", "int", "interface", "internal", "is", "lock", "long",
            "namespace", "new", "null", "object", "operator", "out", "override", "params",
            "private", "protected", "public", "readonly", "record", "ref", "return",
            "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string",
            "struct", "switch", "this", "throw", "true", "try", "typeof", "uint",
            "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", "void",
            "volatile", "while",
        ),
        annotationChar = '@',
        langAliases = listOf("c#", "cs"),
    )

    private val coffeeScript = LanguageSpec(
        name = "coffeescript",
        extensions = listOf("coffee", "litcoffee"),
        keywords = setOf(
            "and", "break", "by", "catch", "class", "continue", "delete", "do", "else",
            "extends", "false", "finally", "for", "if", "in", "instanceof", "is", "isnt",
            "loop", "new", "no", "not", "null", "of", "off", "on", "or", "return",
            "super", "switch", "then", "this", "throw", "true", "try", "typeof", "unless",
            "until", "when", "while", "yes",
        ),
        lineComments = listOf("#"),
        blockComments = listOf("###" to "###"),
        multilineStringDelimiters = listOf(
            "\"\"\"" to "\"\"\"",
            "'''" to "'''",
        ),
        langAliases = listOf("coffee"),
    )

    private val perl = LanguageSpec(
        name = "perl",
        extensions = listOf("pl", "pm", "t"),
        keywords = setOf(
            "continue", "do", "else", "elsif", "eval", "for", "foreach", "given", "goto",
            "if", "last", "local", "my", "next", "no", "package", "redo", "require",
            "return", "state", "sub", "unless", "until", "use", "when", "while",
        ),
        lineComments = listOf("#"),
        blockComments = emptyList(),
    )

    private val ruby = LanguageSpec(
        name = "ruby",
        extensions = listOf("rb", "rake", "gemspec"),
        keywords = setOf(
            "alias", "and", "begin", "break", "case", "class", "def", "defined", "do",
            "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next",
            "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then",
            "true", "undef", "unless", "until", "when", "while", "yield",
        ),
        lineComments = listOf("#"),
        blockComments = listOf("=begin" to "=end"),
    )

    private val swift = LanguageSpec(
        name = "swift",
        extensions = listOf("swift"),
        keywords = setOf(
            "associatedtype", "break", "case", "catch", "class", "continue", "default",
            "defer", "deinit", "do", "else", "enum", "extension", "fallthrough", "false",
            "fileprivate", "for", "func", "guard", "if", "import", "in", "init", "inout",
            "internal", "is", "let", "nil", "open", "operator", "private", "protocol", "public",
            "repeat", "rethrows", "return", "self", "static", "struct", "subscript", "super",
            "switch", "throw", "throws", "true", "try", "typealias", "var", "where", "while",
        ),
        multilineStringDelimiters = listOf("\"\"\"" to "\"\"\""),
        annotationChar = '@',
    )

    private val php = LanguageSpec(
        name = "php",
        extensions = listOf("php", "php3", "php4", "php5", "phtml"),
        keywords = setOf(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
            "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
            "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
            "enum", "eval", "exit", "extends", "final", "finally", "fn", "for", "foreach",
            "function", "global", "goto", "if", "implements", "include", "include_once",
            "instanceof", "insteadof", "interface", "isset", "list", "match", "namespace", "new",
            "null", "or", "print", "private", "protected", "public", "readonly", "require",
            "require_once", "return", "static", "switch", "throw", "trait", "true", "try",
            "unset", "use", "var", "while", "xor", "yield",
        ),
        lineComments = listOf("//", "#"),
    )

    val all: List<LanguageSpec> = listOf(
        kotlin, java, go, python, javascript, typescript, rust, c, cpp,
        json, shell, sql, yaml, toml, xml, css, dart, csharp, coffeeScript,
        perl, ruby, swift, php,
    )
}
