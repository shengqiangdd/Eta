package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeOutlineTest {

    private fun outline(file: String, text: String, limit: Int = 200): List<String> =
        CodeOutline.parse(file, text, limit).map { "${it.kind}:${it.name}@${it.line}" }

    @Test
    fun kotlinSymbolsAndComments() {
        val text = """
            package demo

            // class FakeInComment
            /**
             * fun fakeInKdoc()
             */
            data class User(val id: Int)
            interface Repo
            object Registry
            enum class Mode { A, B }
            fun <T> List<T>.second(): T = this[1]
            internal suspend fun load(id: Int): User = TODO()
        """.trimIndent()

        assertEquals(
            listOf(
                "class:User@7",
                "interface:Repo@8",
                "object:Registry@9",
                "enum:Mode@10",
                "function:second@11",
                "function:load@12",
            ),
            outline("User.kt", text),
        )
    }

    @Test
    fun javaSymbols() {
        val text = """
            public class Service {
                private final Repo repo;
                public Service(Repo repo) { this.repo = repo; }
                @Override public String toString() { return "x"; }
                @interface Marker {}
            }
        """.trimIndent()

        assertEquals(
            listOf("class:Service@1", "function:toString@4", "interface:Marker@5"),
            outline("Service.java", text),
        )
    }

    @Test
    fun goSymbols() {
        val text = """
            package demo

            type Repo struct { id int }
            type Store interface { Save() error }
            func NewRepo() *Repo { return nil }
            func (r *Repo) Save() error { return nil }
        """.trimIndent()

        assertEquals(
            listOf(
                "struct:Repo@3",
                "interface:Store@4",
                "function:NewRepo@5",
                "function:Save@6",
            ),
            outline("repo.go", text),
        )
    }

    @Test
    fun rustSymbols() {
        val text = """
            pub struct Config { pub value: u32 }
            pub fn load() -> Config { Config { value: 1 } }
            impl<T> Repo<T> {
                fn get(&self) {}
            }
            pub mod inner;
        """.trimIndent()

        assertEquals(
            listOf(
                "struct:Config@1",
                "function:load@2",
                "impl:Repo@3",
                "function:get@4",
                "module:inner@6",
            ),
            outline("lib.rs", text),
        )
    }

    @Test
    fun pythonSymbols() {
        val text = """
            class Repo:
                def save(self):
                    pass

            async def load():
                pass
        """.trimIndent()

        assertEquals(
            listOf("class:Repo@1", "function:save@2", "function:load@5"),
            outline("repo.py", text),
        )
    }

    @Test
    fun javascriptSymbols() {
        val text = """
            export class Panel {}
            export function render() {}
            export const Chart = () => null
            const helper = function () {}
            interface Props { a: string }
            type Id = string
        """.trimIndent()

        assertEquals(
            listOf(
                "class:Panel@1",
                "function:render@2",
                "function:Chart@3",
                "function:helper@4",
                "interface:Props@5",
                "type:Id@6",
            ),
            outline("panel.ts", text),
        )
    }

    @Test
    fun swiftSymbolsUseUnifiedKinds() {
        val text = """
            public final class Store {}
            func load() {}
            protocol Repo {}
            extension Store {}
        """.trimIndent()

        assertEquals(
            listOf("class:Store@1", "function:load@2", "interface:Repo@3", "extension:Store@4"),
            outline("Store.swift", text),
        )
    }

    @Test
    fun shellFunctions() {
        val text = """
            # build() {
            build() {
              echo hi
            }
        """.trimIndent()

        assertEquals(listOf("function:build@2"), outline("build.sh", text))
    }

    @Test
    fun unsupportedLanguageIsExplicit() {
        assertEquals(CodeOutline.Language.UNKNOWN, CodeOutline.languageOf("notes.xyz"))
        assertEquals(false, CodeOutline.supports("notes.xyz"))
        assertTrue(CodeOutline.parse("notes.xyz", "class Hidden {}").isEmpty())
    }

    @Test
    fun limitStopsEarlyButKeepsLineNumbers() {
        val text = (1..5).joinToString("\n") { "fun f$it() {}" }
        val symbols = CodeOutline.parse("a.kt", text, limit = 2)
        assertEquals(2, symbols.size)
        assertEquals(listOf("f1", "f2"), symbols.map { it.name })
        assertEquals(listOf(1, 2), symbols.map { it.line })
    }

    @Test
    fun signatureIsTrimmedAndBounded() {
        val text = "fun veryLong() { " + "x".repeat(400) + " }"
        val symbol = CodeOutline.parse("a.kt", text).single()
        assertEquals(200, symbol.signature.length)
        assertTrue(!symbol.signature.startsWith(" "))
    }
}
