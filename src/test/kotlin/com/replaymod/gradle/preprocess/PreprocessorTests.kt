package com.replaymod.gradle.preprocess

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class PreprocessorTests : FunSpec({
    val vars = mapOf(
        "zero" to 0,
        "one" to 1,
        "two" to 2,
        "t" to 1,
        "f" to 0
    )
    with(CommentPreprocessor(vars)) {
        context("evalExpr") {
            test("c-style truthiness of variables") {
                "zero".evalExpr().shouldBeFalse()
                "one".evalExpr().shouldBeTrue()
                "two".evalExpr().shouldBeTrue()
                "t".evalExpr().shouldBeTrue()
                "f".evalExpr().shouldBeFalse()
            }
            test("negation") {
                "!zero".evalExpr().shouldBeTrue()
                "!one".evalExpr().shouldBeFalse()
                "!two".evalExpr().shouldBeFalse()
                "!t".evalExpr().shouldBeFalse()
                "!f".evalExpr().shouldBeTrue()
            }
            test("a == b") {
                "one == 0".evalExpr().shouldBeFalse()
                "one == 1".evalExpr().shouldBeTrue()
                "1 == zero".evalExpr().shouldBeFalse()
                "1 == one".evalExpr().shouldBeTrue()
            }
            test("a != b") {
                "one != 0".evalExpr().shouldBeTrue()
                "one != 1".evalExpr().shouldBeFalse()
                "1 != zero".evalExpr().shouldBeTrue()
                "1 != one".evalExpr().shouldBeFalse()
            }
            test("a > b") {
                "one > 0".evalExpr().shouldBeTrue()
                "one > 1".evalExpr().shouldBeFalse()
                "one > 2".evalExpr().shouldBeFalse()
                "1 > zero".evalExpr().shouldBeTrue()
                "1 > one".evalExpr().shouldBeFalse()
                "1 > two".evalExpr().shouldBeFalse()
            }
            test("a >= b") {
                "one >= 0".evalExpr().shouldBeTrue()
                "one >= 1".evalExpr().shouldBeTrue()
                "one >= 2".evalExpr().shouldBeFalse()
                "1 >= zero".evalExpr().shouldBeTrue()
                "1 >= one".evalExpr().shouldBeTrue()
                "1 >= two".evalExpr().shouldBeFalse()
            }
            test("a < b") {
                "one < 0".evalExpr().shouldBeFalse()
                "one < 1".evalExpr().shouldBeFalse()
                "one < 2".evalExpr().shouldBeTrue()
                "1 < zero".evalExpr().shouldBeFalse()
                "1 < one".evalExpr().shouldBeFalse()
                "1 < two".evalExpr().shouldBeTrue()
            }
            test("a <= b") {
                "one <= 0".evalExpr().shouldBeFalse()
                "one <= 1".evalExpr().shouldBeTrue()
                "one <= 2".evalExpr().shouldBeTrue()
                "1 <= zero".evalExpr().shouldBeFalse()
                "1 <= one".evalExpr().shouldBeTrue()
                "1 <= two".evalExpr().shouldBeTrue()
            }
            test("a && b") {
                "t && t".evalExpr().shouldBeTrue()
                "t && f".evalExpr().shouldBeFalse()
                "f && t".evalExpr().shouldBeFalse()
                "f && f".evalExpr().shouldBeFalse()
            }
            test("a || b") {
                "t || t".evalExpr().shouldBeTrue()
                "t || f".evalExpr().shouldBeTrue()
                "f || t".evalExpr().shouldBeTrue()
                "f || f".evalExpr().shouldBeFalse()
            }
            test("|| and && should nest") {
                "t && t || t".evalExpr().shouldBeTrue()
                "t && f || t".evalExpr().shouldBeTrue()
                "t && f || t && f".evalExpr().shouldBeFalse()
                "t || f && t || f".evalExpr().shouldBeTrue()
                "f || f && t || f".evalExpr().shouldBeFalse()
            }
            test("should allow underscore in numbers") {
                "1_19_02 == 11902".evalExpr().shouldBeTrue()
            }
            test("should desugar dot-separated version literals") {
                "1.19.02 == 11902".evalExpr().shouldBeTrue()
                "1.19.2 == 11902".evalExpr().shouldBeTrue()
                "1.19 == 11900".evalExpr().shouldBeTrue()
                "1.8.9 == 10809".evalExpr().shouldBeTrue()
                "1.8 == 10800".evalExpr().shouldBeTrue()
                "1.7.10 == 10710".evalExpr().shouldBeTrue()
            }
            test("parentheses group conditions") {
                with(CommentPreprocessor(mapOf("A" to 1, "B" to 0, "C" to 1))) {
                    "(A || B) && C".evalExpr() shouldBe true
                    "(A && B) || C".evalExpr() shouldBe true
                    "A || (B && C)".evalExpr() shouldBe true
                    "(A && B)".evalExpr() shouldBe false
                }
            }
            test("parentheses nest") {
                with(CommentPreprocessor(mapOf("A" to 1, "B" to 0, "C" to 1, "D" to 1))) {
                    "((A || B) && (C || D))".evalExpr() shouldBe true
                    "(A || (B && C)) && D".evalExpr() shouldBe true
                    "(A && (B || C))".evalExpr() shouldBe true
                }
            }
            test("negation with parentheses") {
                with(CommentPreprocessor(mapOf("A" to 1, "B" to 0))) {
                    "!(A && B)".evalExpr() shouldBe true
                    "!(A || B)".evalExpr() shouldBe false
                    "!A || B".evalExpr() shouldBe false
                    "!A && B".evalExpr() shouldBe false
                }
            }
            test("parentheses with ranges and comparisons") {
                with(CommentPreprocessor(mapOf("MC" to 12005, "FABRIC" to 1))) {
                    "(MC in 12005..12110) && FABRIC".evalExpr() shouldBe true
                    "(MC >= 12005 && MC < 12110) || FABRIC == 0".evalExpr() shouldBe true
                    "(MC in 12110..12120) || FABRIC == 0".evalExpr() shouldBe false
                }
            }
            test("throws on unbalanced parentheses") {
                with(CommentPreprocessor(mapOf("A" to 1, "B" to 0))) {
                    shouldThrow<CommentPreprocessor.InvalidExpressionException> {
                        "(A || B".evalExpr()
                    }
                    shouldThrow<CommentPreprocessor.InvalidExpressionException> {
                        "A)".evalExpr()
                    }
                    shouldThrow<CommentPreprocessor.InvalidExpressionException> {
                        "()".evalExpr()
                    }
                }
            }
            test("throws on trailing tokens after a complete expression") {
                with(CommentPreprocessor(mapOf("A" to 1))) {
                    shouldThrow<CommentPreprocessor.InvalidExpressionException> {
                        "A A".evalExpr()
                    }
                }
            }
            test("unknown variables should throw") {
                shouldThrow<NoSuchElementException> { "invalid == 0".evalExpr() }
            }
        }
        context("convertSource") {
            fun String.convert() = convertSource(
                PreprocessTask.DEFAULT_KEYWORDS,
                lines(),
                lines().map { it to emptyList() },
                "test.java"
            ).joinToString("\n")

            test("throws on unexpected endif") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#endif".convert() }
            }
            test("throws on unexpected else") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#else".convert() }
            }
            test("throws on unexpected elseif") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#elseif".convert() }
            }
            test("throws on elseif after else") {
                shouldThrow<CommentPreprocessor.ParserException> { """
                    //#if t
                    //#else
                    //#elseif t
                    //#endif
                """.convert() }
            }
            test("throws on else after else") {
                shouldThrow<CommentPreprocessor.ParserException> { """
                    //#if t
                    //#else
                    //#else
                    //#endif
                """.convert() }
            }
            test("throws on content after else") {
                shouldThrow<CommentPreprocessor.ParserException> { """
                    //#if t
                    //#else#if t
                    //#endif
                """.convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if t\n//#else t\n//#endif".convert() }
            }
            test("allows comment after else") {
                """
                    //#if f
                    //#else // comment is fine
                    //$$ print(ok)
                    //#endif
                """.convert()
            }
            test("swapwhen rewrites the line when the condition holds") {
                val out = """
                    package com.example;
                    import com.example.a.Old; //#swapwhen t com.example.b.New
                    class C {}
                """.convert()
                out.lines().map { it.trim() }.contains("import com.example.b.New;") shouldBe true
            }
            test("swapwhen keeps the line and drops the directive otherwise") {
                val out = """
                    package com.example;
                    import com.example.a.Old; //#swapwhen f com.example.b.New
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("import com.example.a.Old;") shouldBe true
                outLines.none { it.contains("swapwhen") } shouldBe true
            }
            test("swapwhen also applies to code lines") {
                val out = "class C {\n    int x = 1; //#swapwhen t int x = 2;\n}".convert()
                out.lines().map { it.trim() }.contains("int x = 2;") shouldBe true
            }
            test("inline case replaces the code it follows when the condition holds") {
                val out = "class C {\n    int x = /*#case*/1/*?t ? 2*/;\n}".convert()
                out.lines().map { it.trim() }.contains("int x = 2;") shouldBe true
            }
            test("inline case keeps the code as written and drops the directive otherwise") {
                val out = "class C {\n    int x = /*#case*/1/*?f ? 2*/;\n}".convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("int x = 1;") shouldBe true
                outLines.none { it.contains("#swap") || it.contains("#when") } shouldBe true
            }
            test("inline case applies to import statements") {
                val out = """
                    package com.example;
                    import com.example.a./*#case*/Old/*?t ? New*/;
                    class C {}
                """.convert()
                out.lines().map { it.trim() }.contains("import com.example.a.New;") shouldBe true
            }
            test("inline case takes the first condition that holds") {
                val out = "class C {\n    int x = /*#case*/1/*?one ? 2*//*?two ? 3*/;\n}".convert()
                out.lines().map { it.trim() }.contains("int x = 2;") shouldBe true
            }
            test("inline case with descending conditions picks the newest one that holds") {
                val out = """
                    class C {
                        int x = /*#case*/0/*?two >= 2 ? 3*//*?two >= 1 ? 2*//*?one >= 1 ? 1*/;
                    }
                """.convert()
                out.lines().map { it.trim() }.contains("int x = 3;") shouldBe true
            }
            test("inline case falls through to a lower condition when a higher one fails") {
                val out = """
                    class C {
                        int x = /*#case*/0/*?two >= 3 ? 3*//*?one >= 1 ? 1*/;
                    }
                """.convert()
                out.lines().map { it.trim() }.contains("int x = 1;") shouldBe true
            }
            test("inline case keeps the baseline when no condition holds") {
                val out = """
                    class C {
                        int x = /*#case*/0/*?two >= 3 ? 3*//*?two >= 4 ? 4*/;
                    }
                """.convert()
                out.lines().map { it.trim() }.contains("int x = 0;") shouldBe true
            }
            test("inline case does not evaluate conditions that follow the winner") {
                val out = "class C {\n    int x = /*#case*/1/*?t ? 2*//*?bogus >= 1 ? 3*/;\n}".convert()
                out.lines().map { it.trim() }.contains("int x = 2;") shouldBe true
            }
            test("throws on an unusable condition in an inline case") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "class C {\n    int x = /*#case*/1/*?bogus >= 1 ? 2*/;\n}".convert()
                }
            }
            test("several inline cases may share one line") {
                val out = "class C {\n    int x = /*#case*/1/*?t ? 2*/ + /*#case*/3/*?f ? 4*/;\n}".convert()
                out.lines().map { it.trim() }.contains("int x = 2 + 3;") shouldBe true
            }
            test("inline case keeps whitespace around the code it replaces") {
                val out = "class C {\n    register(/*#case*/ Old.class /*?t ? New.class */);\n}".convert()
                out.lines().map { it.trim() }.contains("register( New.class );") shouldBe true
            }
            test("inline case is ignored inside an inactive branch") {
                val out = """
                    class C {
                        //#if f
                        int x = /*#case*/1/*?t ? 2*/;
                        //#endif
                    }
                """.convert()
                out.lines().any { it.contains("#case") } shouldBe true
            }
            test("throws on an inline branch without a condition separator") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "class C {\n    int x = /*#case*/1/*?t 2*/;\n}".convert()
                }
            }
            test("case branch becomes code when its condition holds") {
                val out = """
                    //#case
                    //?t codeA();
                    //?f codeB();
                    //#endcase
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("codeA();") shouldBe true
                outLines.contains("//?f codeB();") shouldBe true
            }
            test("throws when no branch in a case group matches") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    """
                        //#case
                        //?f codeB();
                        //#endcase
                        class C {}
                    """.convert()
                }
            }
            test("uses the longest evaluable prefix as the condition") {
                val out = """
                    //#case
                    //?t >= 0 codeA();
                    //#endcase
                    class C {}
                """.convert()
                out.lines().map { it.trim() }.contains("codeA();") shouldBe true
            }
            test("throws on malformed swapwhen") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "int x = 1; //#swapwhen\nclass C {}".convert()
                }
            }
            test("throws on malformed case branch") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "//#case\n//?\n//#endcase\nclass C {}".convert()
                }
            }
            test("throws on unusable case condition") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "//#case\n//?bogus >= 1 codeA();\n//#endcase\nclass C {}".convert()
                }
            }
            test("case markers survive so that a second pass still sees the group") {
                val out = """
                    //#case
                    //?t codeA();
                    //#endcase
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("//#case") shouldBe true
                outLines.contains("//#endcase") shouldBe true
            }
            test("throws on an unterminated case block") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "//#case\n//?t codeA();\nclass C {}".convert()
                }
            }
            test("throws on an unexpected case end") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#endcase\nclass C {}".convert() }
            }
            test("throws on a nested case block") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "//#case\n//#case\n//#endcase\n//#endcase\nclass C {}".convert()
                }
            }
            test("multi-line block expands when its branch is active") {
                val out = """
                    class C {
                        //#if t
                        /*$$
                        int a = 1;
                        int b = 2;
                        $$*/
                        //#endif
                    }
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("int a = 1;") shouldBe true
                outLines.contains("int b = 2;") shouldBe true
                outLines.none { it.startsWith("/*$$") || it.startsWith("$$*/") } shouldBe true
            }
            test("multi-line block stays a comment when its branch is inactive") {
                val out = """
                    class C {
                        //#if f
                        /*$$
                        int a = 1;
                        $$*/
                        //#endif
                    }
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("/*$$") shouldBe true
                outLines.contains("$$*/") shouldBe true
            }
            test("throws on unterminated multi-line block") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "class C {\n/*$$\nint a = 1;\n".convert()
                }
            }
            test("trailing case condition keeps the line when it holds") {
                val out = "//#case\nclass C {\n    int a = 1; //?t\n}\n//#endcase".convert()
                out.lines().map { it.trim() }.contains("int a = 1;") shouldBe true
            }
            test("throws when no branch in a case group matches (trailing form)") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    """
                       //#case
                       class C {
                           int a = 1; //?f
                       }
                       //#endcase
                   """.convert()
                }
            }
            test("case block mixes plain and prefixed alternatives") {
                // With "first match wins", once the trailing `//?t` on the first line has matched, the later
                // `//?t` line is skipped and kept as-is.
                val out = """
                    //#case
                    int a = 1; //?t
                    //?t int b = 2;
                    //#endcase
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("int a = 1;") shouldBe true
                outLines.contains("//?t int b = 2;") shouldBe true
                outLines.none { it == "int b = 2;" } shouldBe true
            }
            test("standalone line-level //? acts like a single-line //#if") {
                val out = "//?t codeA();\nclass C {}".convert()
                out.lines().map { it.trim() }.contains("codeA();") shouldBe true
                val out2 = "//?f codeA();\nclass C {}".convert()
                out2.lines().map { it.trim() }.contains("//?f codeA();") shouldBe true
            }
            test("standalone trailing //? acts like a single-line //#if") {
                val out = "class C {\n    int a = 1; //?t\n}".convert()
                out.lines().map { it.trim() }.contains("int a = 1;") shouldBe true
                val out2 = "class C {\n    int a = 1; //?f\n}".convert()
                out2.lines().map { it.trim() }.any {
                    it.startsWith("//$$") && it.contains("int a = 1;")
                } shouldBe true
            }
            test("only the first matching alternative in a case group is activated") {
                val out = """
                    //#case
                    //?t codeA();
                    //?t codeB();
                    //#endcase
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("codeA();") shouldBe true
                outLines.contains("//?t codeB();") shouldBe true
                outLines.none { it == "codeB();" } shouldBe true
            }
            test("alternatives after a match are skipped without evaluating their conditions") {
                // Once `//?t` has matched, a later `//?bogus ...` must not be parsed: it may reference variables
                // that only exist for a different version.
                val out = """
                    //#case
                    //?t codeA();
                    //?bogus >= 1 codeB();
                    //#endcase
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("codeA();") shouldBe true
                outLines.contains("//?bogus >= 1 codeB();") shouldBe true
            }
            test("standalone //? inside an inactive //#if branch stays commented") {
                val out = """
                    //#if f
                    //?t codeA();
                    //#endif
                """.convert()
                out.lines().map { it.trim() }.contains("//?t codeA();") shouldBe true
            }
            test("//?t at the end of a case group acts as a default branch") {
                val out = """
                    //#case
                    //?f codeA();
                    //?t codeB();
                    //#endcase
                    class C {}
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("codeB();") shouldBe true
                outLines.none { it.contains("//?t") } shouldBe true
            }
            test("//#elif is an alias for //#elseif") {
                val out = """
                    //#if f
                    //$$ codeA();
                    //#elif t
                    //$$ codeB();
                    //#endif
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("//$$ codeA();") shouldBe true
                outLines.contains("codeB();") shouldBe true
            }
            test("range syntax MC in A..B is inclusive on the low end and exclusive on the high end") {
                with(CommentPreprocessor(mapOf("MC" to 12005))) {
                    "MC in 12005..12110".evalExpr() shouldBe true
                    "MC in 12110..12120".evalExpr() shouldBe false
                    "MC in 11900..12005".evalExpr() shouldBe false
                }
                with(CommentPreprocessor(mapOf("MC" to 12109))) {
                    "MC in 12005..12110".evalExpr() shouldBe true
                }
                with(CommentPreprocessor(mapOf("MC" to 12110))) {
                    "MC in 12005..12110".evalExpr() shouldBe false
                }
            }
            test("range syntax accepts dot-separated version literals") {
                with(CommentPreprocessor(mapOf("MC" to 12005))) {
                    "MC in 1.20.5..1.21.10".evalExpr() shouldBe true
                }
            }
            test("range syntax can be combined with && and ||") {
                with(CommentPreprocessor(mapOf("MC" to 12005, "X" to 1))) {
                    "MC in 12005..12110 && X == 1".evalExpr() shouldBe true
                    "MC in 12005..12110 && X == 0".evalExpr() shouldBe false
                    "MC in 12005..12110 || X == 0".evalExpr() shouldBe true
                }
            }
            test("$$*/ can sit at the end of the last line of a block") {
                val out = """
                    class C {
                        //#if t
                        /*$$
                        int a = 1;
                        return i > 0;$$*/
                        //#endif
                    }
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("int a = 1;") shouldBe true
                outLines.contains("return i > 0;") shouldBe true
                outLines.none { it.startsWith("/*$$") || it.endsWith("$$*/") } shouldBe true
            }
            test("$$*/ at the end of a line stays a comment inside an inactive branch") {
                val out = """
                    class C {
                        //#if f
                        /*$$
                        int a = 1;
                        return i > 0;$$*/
                        //#endif
                    }
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("/*$$") shouldBe true
                outLines.any { it.endsWith("$$*/") } shouldBe true
            }
            test("/*$$ may be followed by code on the same line") {
                val out = """
                    class C {
                        //#if t
                        /*$$ int a = 1;
                        int b = 2;
                        $$*/
                        //#endif
                    }
                """.convert()
                val outLines = out.lines().map { it.trim() }
                outLines.contains("int a = 1;") shouldBe true
                outLines.contains("int b = 2;") shouldBe true
                outLines.none { it.startsWith("/*$$") } shouldBe true
            }
            test("throws on trailing case condition without a condition") {
                shouldThrow<CommentPreprocessor.ParserException> {
                    "//#case\nclass C {\n    int a = 1; //?\n}\n//#endcase".convert()
                }
            }
            test("throws on missing endif") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#if t".convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if t\n//#if t\n//#endif".convert() }
            }
            test("throws on missing space") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#ift\n//#endif".convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if f\n//#elseift\n//#endif".convert() }
            }
            test("throws on empty if condition") {
                shouldThrow<CommentPreprocessor.ParserException> { "//#if\n//#endif".convert() }
                shouldThrow<CommentPreprocessor.ParserException> { "//#if f\n//#elseif\n//#endif".convert() }
            }
            test("if t .. endif") {
                """
                    //#if t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#endif
                """)
            }
            test("if f .. endif") {
                """
                    //#if f
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#endif
                """)
            }
            test("if t .. else .. endif") {
                """
                    //#if t
                    code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#else
                    //$$ code
                    //#endif
                """)
            }
            test("if f .. else .. endif") {
                """
                    //#if f
                    //$$ code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
            }
            test("if t .. elseif f .. endif") {
                """
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif f
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    code
                    //#elseif f
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif f
                    //$$ code
                    //#endif
                """)
            }
            test("if f .. elseif t .. endif") {
                """
                    //#if f
                    code
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#elseif t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """)
            }
            test("if t .. elseif t .. endif") {
                """
                    //#if t
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif t
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif t
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif t
                    //$$ code
                    //#endif
                """)
            }
            test("if .. elseif .. else .. endif") {
                """
                    //#if f
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                    code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if t
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    code
                    //#elseif t
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
            }
            test("multiple elseifs") {
                """
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif t
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif f
                    //$$ code
                    //#elseif t
                    code
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """)
            }
            test("nested if") {
                """
                    //#if f
                        //#if f
                        code
                        //#else
                        //$$ code
                        //#endif
                    //#else
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#else
                        //#if f
                        //$$ code
                        //#else
                        code
                        //#endif
                    //#endif
                """)
            }
            test("nested elseifs") {
                """
                    //#if f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#elseif t
                    //$$ code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#elseif t
                    code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#elseif t
                        //#if f
                        code
                        //#else
                        code
                        //#endif
                    //#elseif f
                    code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif t
                        //#if f
                        //$$ code
                        //#else
                        code
                        //#endif
                    //#elseif f
                    //$$ code
                    //#else
                    //$$ code
                    //#endif
                """)
                """
                    //#if f
                    code
                    //#elseif f
                        //#if f
                        code
                        //#else
                        code
                        //#endif
                    //#elseif f
                    code
                    //#else
                    code
                    //#endif
                """.convert().shouldBe("""
                    //#if f
                    //$$ code
                    //#elseif f
                        //#if f
                        //$$ code
                        //#else
                        //$$ code
                        //#endif
                    //#elseif f
                    //$$ code
                    //#else
                    code
                    //#endif
                """)
                """
                    //#if t
                    //#elseif t
                        //#if t
                        code
                        //#else
                        //#endif
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    //#elseif t
                        //#if t
                        //$$ code
                        //#else
                        //#endif
                    //#endif
                """)
                """
                    //#if t
                    //#elseif t
                        //#if t
                        //#endif
                        code
                    //#endif
                """.convert().shouldBe("""
                    //#if t
                    //#elseif t
                        //#if t
                        //#endif
                    //$$     code
                    //#endif
                """)
            }
            test("uses mapped source for unaffected lines") {
                convertSource(
                    PreprocessTask.DEFAULT_KEYWORDS,
                    listOf("//#if t", "original", "//#endif"),
                    listOf("//#if t", "mapped", "//#endif").map { it to emptyList() },
                    "test.java"
                ).shouldBe(listOf("//#if t", "mapped", "//#endif"))
            }
            test("uses original source for newly commented lines") {
                convertSource(
                    PreprocessTask.DEFAULT_KEYWORDS,
                    listOf("//#if f", "original", "//#endif"),
                    listOf("//#if f", "mapped", "//#endif").map { it to emptyList() },
                    "test.java"
                ).shouldBe(listOf("//#if f", "//$$ original", "//#endif"))
            }
            test("fails when there are errors in unaffected lines") {
                with (CommentPreprocessor(vars)) {
                    convertSource(
                        PreprocessTask.DEFAULT_KEYWORDS,
                        listOf("//#if t", "original", "//#endif"),
                        listOf(
                            "//#if t" to emptyList(),
                            "mapped" to listOf("err1", "err2"),
                            "//#endif" to emptyList()
                        ),
                        "test.java"
                    )
                    fail.shouldBeTrue()
                }
            }
            test("ignores errors in commented lines") {
                with (CommentPreprocessor(vars)) {
                    convertSource(
                        PreprocessTask.DEFAULT_KEYWORDS,
                        listOf("//#if f", "original", "//#endif"),
                        listOf(
                            "//#if f" to emptyList(),
                            "mapped" to listOf("err1", "err2"),
                            "//#endif" to emptyList()
                        ),
                        "test.java"
                    )
                    fail.shouldBeFalse()
                }
            }
        }
    }
})