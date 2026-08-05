package io.github.yearsyan.pi.chat

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SlashCommandTest {
    private val commands =
        listOf(
            SlashCommand("compact", "Compact context", SlashCommandSource.BuiltIn),
            SlashCommand("fix-tests", "Fix failing tests", SlashCommandSource.Prompt),
            SlashCommand("skill:review", "Review changed code", SlashCommandSource.Skill),
        )

    @Test
    fun slashTokenFiltersCommandsUntilArgumentsBegin() {
        assertEquals(
            listOf("compact", "fix-tests", "skill:review"),
            matchingSlashCommands("/", commands).map { it.name },
        )
        assertEquals(
            listOf("skill:review"),
            matchingSlashCommands("/rev", commands).map { it.name },
        )
        assertEquals(emptyList(), matchingSlashCommands("/skill:review ", commands))
        assertEquals(emptyList(), matchingSlashCommands("plain text", commands))
    }

    @Test
    fun parsesCompactInstructionsWithoutMatchingLongerNames() {
        assertEquals(
            SlashInvocation("compact", "Focus on code changes"),
            parseSlashInvocation("  /compact   Focus on code changes  "),
        )
        assertEquals("compactness", parseSlashInvocation("/compactness")?.name)
        assertNull(parseSlashInvocation("/"))
    }

    @Test
    fun compactRpcIncludesOnlyNonBlankInstructions() {
        val plain = buildCompactCommand("request-1", "")
        assertEquals("request-1", plain.getValue("id").jsonPrimitive.content)
        assertEquals("compact", plain.getValue("type").jsonPrimitive.content)
        assertFalse("customInstructions" in plain)

        val instructed = buildCompactCommand("request-2", "  Keep decisions  ")
        assertEquals("Keep decisions", instructed.getValue("customInstructions").jsonPrimitive.content)
    }

    @Test
    fun expandedSkillHistoryUsesCompactInvocation() {
        val expanded =
            """
            <skill name="review" location="/skills/review/SKILL.md">
            References are relative to /skills/review.

            A very long skill body.
            </skill>

            Check the current diff
            """.trimIndent()

        assertEquals("/skill:review Check the current diff", compactSkillInvocation(expanded))
        assertEquals("ordinary prompt", compactSkillInvocation("ordinary prompt"))
    }
}
