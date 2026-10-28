package dev.shibasis.reaktor.auth.fga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

object DriveModel : FgaSchema() {
    object User : FgaType("user")

    object Folder : FgaType("folder") {
        val parent: FgaRelation by relation { direct(Folder) }
        val owner: FgaRelation by relation { direct(User) or (Folder.owner from parent) }
        val viewer: FgaRelation by relation { direct(User, User.wildcard) or owner }
        val blocked: FgaRelation by relation { direct(User) }
        val canView: FgaRelation by relation { viewer butNot blocked }
    }

    override val types get() = listOf(User, Folder)
}

object CrossedModel : FgaSchema() {
    object User : FgaType("user")

    object Folder : FgaType("folder") {
        val owner: FgaRelation by relation { direct(User) }
    }

    object Document : FgaType("document") {
        val owner: FgaRelation by relation { Folder.owner }
    }

    override val types get() = listOf(User, Folder, Document)
}

class SchemaTest {
    private val drive = """
        model
          schema 1.1

        type user

        type folder
          relations
            define parent: [folder]
            define owner: [user] or owner from parent
            define viewer: [user, user:*] or owner
            define blocked: [user]
            define can_view: viewer but not blocked
    """.trimIndent() + "\n"

    private val driveKotlin = """
        import dev.shibasis.reaktor.auth.fga.FgaRelation
        import dev.shibasis.reaktor.auth.fga.FgaSchema
        import dev.shibasis.reaktor.auth.fga.FgaType

        object DriveModel : FgaSchema() {
            object User : FgaType("user")

            object Folder : FgaType("folder") {
                val parent: FgaRelation by relation { direct(Folder) }
                val owner: FgaRelation by relation { direct(User) or (Folder.owner from parent) }
                val viewer: FgaRelation by relation { direct(User, User.wildcard) or owner }
                val blocked: FgaRelation by relation { direct(User) }
                val canView: FgaRelation by relation { viewer butNot blocked }
            }

            override val types get() = listOf(User, Folder)
        }
    """.trimIndent() + "\n"

    @Test
    fun aKotlinSchemaIsTheSameModelAsItsOpenFgaText() {
        assertEquals(FgaDsl.parse(drive).model, DriveModel.model)
        assertEquals(drive, DriveModel.dsl())
        assertEquals(emptyList(), DriveModel.issues)
    }

    @Test
    fun anOpenFgaModelPrintsAsTheKotlinThatDefinesIt() {
        assertEquals(driveKotlin, FgaKotlin.print(FgaDsl.parse(drive).model!!, "DriveModel"))
    }

    @Test
    fun relationNamesTheKotlinCannotSpellAreKeptExplicit() {
        val model = FgaDsl.parse("model\n  schema 1.1\n\ntype user\n\ntype doc\n  relations\n    define name: [user]\n    define read-only: [user]\n    define in: name or read-only\n").model!!
        val printed = FgaKotlin.print(model, "Docs", "app.access")
        assertTrue(printed.startsWith("package app.access\n\n"))
        assertTrue("val nameRelation: FgaRelation by relation(\"name\") { direct(User) }" in printed)
        assertTrue("val readOnly: FgaRelation by relation(\"read-only\") { direct(User) }" in printed)
        assertTrue("val inRelation: FgaRelation by relation(\"in\") { nameRelation or readOnly }" in printed)
    }

    @Test
    fun aRelationOfAnotherTypeCannotBeComputed() {
        val failure = assertFailsWith<IllegalArgumentException> { CrossedModel.model }
        assertTrue("folder#owner belongs to folder" in failure.message.orEmpty())
    }

    @Test
    fun theModelIsWrittenInOpenFgaJson() {
        val json = """
            {
              "schema_version": "1.1",
              "type_definitions": [
                {
                  "type": "user",
                  "relations": {},
                  "metadata": null
                },
                {
                  "type": "folder",
                  "relations": {
                    "parent": {
                      "this": {}
                    },
                    "owner": {
                      "union": {
                        "child": [
                          {
                            "this": {}
                          },
                          {
                            "tupleToUserset": {
                              "tupleset": {
                                "relation": "parent"
                              },
                              "computedUserset": {
                                "relation": "owner"
                              }
                            }
                          }
                        ]
                      }
                    },
                    "viewer": {
                      "union": {
                        "child": [
                          {
                            "this": {}
                          },
                          {
                            "computedUserset": {
                              "relation": "owner"
                            }
                          }
                        ]
                      }
                    },
                    "blocked": {
                      "this": {}
                    },
                    "can_view": {
                      "difference": {
                        "base": {
                          "computedUserset": {
                            "relation": "viewer"
                          }
                        },
                        "subtract": {
                          "computedUserset": {
                            "relation": "blocked"
                          }
                        }
                      }
                    }
                  },
                  "metadata": {
                    "relations": {
                      "parent": {
                        "directly_related_user_types": [
                          {
                            "type": "folder"
                          }
                        ]
                      },
                      "owner": {
                        "directly_related_user_types": [
                          {
                            "type": "user"
                          }
                        ]
                      },
                      "viewer": {
                        "directly_related_user_types": [
                          {
                            "type": "user"
                          },
                          {
                            "type": "user",
                            "wildcard": {}
                          }
                        ]
                      },
                      "blocked": {
                        "directly_related_user_types": [
                          {
                            "type": "user"
                          }
                        ]
                      },
                      "can_view": {
                        "directly_related_user_types": []
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(json, DriveModel.json())
        assertEquals(DriveModel.model, FgaJson.decode(json).getOrThrow())
    }

    @Test
    fun readingJsonAcceptsTheApiEnvelopeAndRefusesConditions() {
        val envelope = """{"authorization_model":{"id":"01J","schema_version":"1.1","type_definitions":[{"type":"user"},{"type":"team","relations":{"member":{"this":{}},"lead":{"intersection":{"child":[{"computedUserset":{"object":"","relation":"member"}},{"this":{}}]}}},"metadata":{"relations":{"member":{"directly_related_user_types":[{"type":"user"},{"type":"team","relation":"member"}]},"lead":{"directly_related_user_types":[{"type":"user"}]}}}}]}}"""
        val model = FgaJson.decode(envelope).getOrThrow()
        assertEquals("[user, team#member]", FgaDsl.print(model.rewrite("team", "member")!!))
        assertEquals("member and [user]", FgaDsl.print(model.rewrite("team", "lead")!!))
        val conditioned = """{"schema_version":"1.1","type_definitions":[{"type":"doc","relations":{"viewer":{"this":{}}},"metadata":{"relations":{"viewer":{"directly_related_user_types":[{"type":"user","condition":"in_hours"}]}}}}]}"""
        assertTrue("condition" in FgaJson.decode(conditioned).exceptionOrNull()?.message.orEmpty())
    }
}
