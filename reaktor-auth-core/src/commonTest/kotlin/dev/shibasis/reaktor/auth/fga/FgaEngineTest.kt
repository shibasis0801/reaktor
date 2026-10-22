package dev.shibasis.reaktor.auth.fga

import kotlin.test.*

class FgaEngineTest {
    private val drive = """
        model
          schema 1.1

        type user

        type group
          relations
            define member: [user, group#member]

        type folder
          relations
            define parent: [folder]
            define owner: [user]
            define viewer: [user, user:*, group#member] or owner or viewer from parent

        type doc
          relations
            define parent: [folder]
            define owner: [user]
            define editor: [user] or owner
            define blocked: [user]
            define viewer: ([user, group#member] or editor or viewer from parent) but not blocked
            define can_share: owner and editor
    """.trimIndent() + "\n"

    private val model = FgaDsl.parse(drive).model!!

    private val facts = listOf(
        "group:eng#member@user:anne", "group:eng#member@group:design#member", "group:design#member@user:beth",
        "folder:root#owner@user:carl", "folder:docs#parent@folder:root", "doc:plan#parent@folder:docs",
        "doc:plan#viewer@group:eng#member", "doc:plan#blocked@user:dave", "group:eng#member@user:dave",
        "folder:public#viewer@user:*", "doc:open#parent@folder:public", "doc:plan#owner@user:erin",
    ).map { RelationTuple.parse(it)!! }

    private val fga = Fga(model, facts)

    private fun user(id: String) = Subject.of("user", id)
    private fun doc(id: String) = ObjectRef("doc", id)

    @Test fun theDslRoundTripsThroughThePrinter() {
        assertEquals(drive, FgaDsl.print(model))
        assertEquals(model, FgaDsl.parse(FgaDsl.print(model)).model)
        assertEquals(listOf(TypeRef("user"), TypeRef("user", wildcard = true), TypeRef("group", "member")), model.directTypes("folder", "viewer"))
        assertEquals(emptyList(), ModelRules.issues(model))
    }

    @Test fun dslMistakesNameTheirLineAndColumn() {
        val mixed = FgaDsl.parse("model\n  schema 1.1\ntype user\ntype doc\n  relations\n    define owner: [user]\n    define viewer: [user] or owner and owner\n")
        assertNull(mixed.model)
        assertEquals(DslIssue(7, 36, "Wrap mixed operators in parentheses"), mixed.issues.single())
        assertEquals("Conditions are not supported yet",
            FgaDsl.parse("model\n  schema 1.1\ntype user\ntype doc\n  relations\n    define viewer: [user with recent]\n").issues.single().message)
        assertEquals(listOf("The model must declare \"schema 1.1\""), FgaDsl.parse("model\ntype user\n").issues.map { it.message })
        assertEquals("relations must follow a type", FgaDsl.parse("model\n  schema 1.1\n  relations\n").issues.single().message)
        assertEquals("Expected \")\"", FgaDsl.parse("model\n  schema 1.1\ntype doc\n  relations\n    define a: [doc]\n    define b: (a or a\n").issues.single().message)
        assertTrue(FgaDsl.parse("model\n  schema 1.1\n# a comment\ntype user # trailing\n").ok)
    }

    @Test fun validationFindsBrokenReferencesAndRelationsNothingCanGrant() {
        val broken = FgaDsl.parse("""
            model
              schema 1.1
            type user
            type doc
              relations
                define owner: [usr]
                define editor: writer
                define parent: [doc#owner]
                define viewer: viewer from parent
                define reader: owner from editor
        """.trimIndent()).model!!
        assertEquals(listOf(
            "doc#owner: refers to the unknown type usr",
            "doc#editor: refers to writer, which doc does not define",
            "doc#viewer: inherits from parent, which must be a plain [type list] without usersets or wildcards",
            "doc#reader: inherits from editor, which must be a plain [type list] without usersets or wildcards",
        ), ModelRules.issues(broken).map { it.toString() })
        val loop = FgaDsl.parse("model\n  schema 1.1\ntype doc\n  relations\n    define a: b\n    define b: a or c\n    define c: [doc]\n    define d: e\n    define e: d\n").model!!
        assertEquals(listOf("doc#d", "doc#e"), ModelRules.issues(loop).map { "${it.type}#${it.relation}" })
        assertEquals("doc#can_share is computed; it cannot be granted directly", ModelRules.tupleIssue(model, RelationTuple.parse("doc:plan#can_share@user:anne")!!))
        assertEquals("doc#viewer accepts user, group#member, not folder", ModelRules.tupleIssue(model, RelationTuple.parse("doc:plan#viewer@folder:x")!!))
        assertNull(ModelRules.tupleIssue(model, RelationTuple.parse("doc:plan#viewer@group:eng#member")!!))
    }

    @Test fun checkFollowsUsersetsParentsAndSetOperators() {
        assertTrue(fga.check(user("anne"), "viewer", doc("plan")).allowed)
        assertTrue(fga.check(user("beth"), "viewer", doc("plan")).allowed)
        assertTrue(fga.check(user("carl"), "viewer", doc("plan")).allowed)
        assertTrue(fga.check(user("erin"), "viewer", doc("plan")).allowed)
        assertFalse(fga.check(user("dave"), "viewer", doc("plan")).allowed)
        assertTrue(fga.check(user("dave"), "viewer", doc("open")).allowed)
        assertTrue(fga.check(user("erin"), "can_share", doc("plan")).allowed)
        assertFalse(fga.check(user("anne"), "can_share", doc("plan")).allowed)
        assertTrue(fga.check(Subject.parse("group:design#member")!!, "viewer", doc("plan")).allowed)
        assertFalse(fga.check(user("zoe"), "viewer", doc("plan")).allowed)
        assertTrue(fga.check(user("zoe"), "viewer", doc("plan"), listOf(RelationTuple.parse("group:design#member@user:zoe")!!)).allowed)
        assertEquals("doc has no relation approver", fga.check(user("anne"), "approver", doc("plan")).error)
    }

    @Test fun anAllowedCheckExplainsItselfDownToTheTuples() {
        val carl = fga.check(user("carl"), "viewer", doc("plan")).explanation!!
        assertEquals(listOf("doc:plan#viewer", "doc:plan#viewer", "doc:plan#viewer", "folder:docs#viewer", "folder:docs#viewer", "folder:root#viewer", "folder:root#viewer", "folder:root#owner"),
            carl.reason.map { "${it.target}#${it.relation}" })
        assertEquals(setOf("doc:plan#parent@folder:docs", "folder:docs#parent@folder:root", "folder:root#owner@user:carl"), carl.facts.map { it.toString() }.toSet())
        val dave = fga.check(user("dave"), "viewer", doc("plan")).explanation!!
        assertEquals("Excluded", dave.children.last().note)
        val open = fga.check(user("dave"), "viewer", doc("open")).explanation!!
        assertEquals("folder:public#viewer@user:*", open.facts.last().toString())
    }

    @Test fun cyclesGrantNothingAndDeepChainsStopWithAnError() {
        val groups = AuthorizationModel(listOf(TypeDefinition("user"), TypeDefinition("group", listOf(RelationDefinition("member",
            Rewrite.Direct(listOf(TypeRef("user"), TypeRef("group", "member"))))))))
        val loop = listOf("group:a#member@group:b#member", "group:b#member@group:a#member").map { RelationTuple.parse(it)!! }
        assertFalse(Fga(groups, loop).check(user("x"), "member", ObjectRef("group", "a")).allowed)
        assertTrue(Fga(groups, loop + RelationTuple.parse("group:b#member@user:x")!!).check(user("x"), "member", ObjectRef("group", "a")).allowed)
        val chain = (0 until 30).map { RelationTuple.parse("group:g$it#member@group:g${it + 1}#member")!! } + RelationTuple.parse("group:g30#member@user:x")!!
        val deep = Fga(groups, chain).check(user("x"), "member", ObjectRef("group", "g0"))
        assertFalse(deep.allowed)
        assertEquals("Resolution went deeper than 25 steps at group:g26#member", deep.error)
        assertTrue(Fga(groups, chain, maxDepth = 40).check(user("x"), "member", ObjectRef("group", "g0")).allowed)
    }

    @Test fun listObjectsListUsersAndExpandAgreeWithCheck() {
        assertEquals(listOf(doc("open"), doc("plan")), fga.listObjects("doc", "viewer", user("anne")))
        assertEquals(listOf(doc("open")), fga.listObjects("doc", "viewer", user("dave")))
        assertEquals(listOf("user:anne", "user:beth", "user:carl", "user:erin"), fga.listUsers(doc("plan"), "viewer", TypeRef("user")).map { it.toString() })
        assertEquals(listOf("group:design#member", "group:eng#member"), fga.listUsers(doc("plan"), "viewer", TypeRef("group", "member")).map { it.toString() })
        assertEquals(listOf("user:*"), fga.listUsers(doc("open"), "viewer", TypeRef("user", wildcard = true)).map { it.toString() })
        val tree = fga.expand(doc("plan"), "viewer") as UsersetTree.Difference
        val union = tree.base as UsersetTree.Union
        assertEquals(listOf(Subject.parse("group:eng#member")), (union.children[0] as UsersetTree.Leaf).subjects)
        assertEquals(listOf(ObjectRef("folder", "docs")), (union.children[2] as UsersetTree.FromParents).parents)
        assertEquals(listOf(user("dave")), ((tree.subtract as UsersetTree.Computed).tree as UsersetTree.Leaf).subjects)
    }

    @Test fun storeFilesRoundTripAndRunTheirTests() {
        val store = FgaStore("drive", "drive.fga", facts.map { it.key }, listOf(
            FgaTest("sharing", tuples = listOf(TupleKey("user:fay", "owner", "doc:memo")), check = listOf(
                CheckAssertion("user:fay", "doc:memo", mapOf("can_share" to true, "viewer" to true)),
                CheckAssertion("user:dave", "doc:plan", mapOf("viewer" to true)),
            ), listObjects = listOf(ListObjectsAssertion("user:anne", "doc", mapOf("viewer" to listOf("doc:plan", "doc:open"))))),
        ))
        assertEquals(store, FgaStore.decode(store.encode()))
        assertTrue(store.encode().contains("\"model_file\": \"drive.fga\""))
        val outcomes = FgaTests.run(model, store)
        assertEquals(listOf(true, true, false, true), outcomes.map { it.passed })
        assertEquals("user:dave viewer doc:plan", outcomes[2].question)
        assertEquals("false", outcomes[2].actual)
        val grant = RelationTuple.parse("doc:memo#viewer@user:gus")!!
        assertEquals(store.tuples.size + 1, store.with(grant).with(grant).tuples.size)
        assertEquals(store.tuples, store.with(grant).without(grant).tuples)
    }

    @Test fun rewritesCanBeEditedWithoutLosingTheirShape() {
        val viewer = model.rewrite("folder", "viewer")!!
        assertEquals("[user, user:*, group#member, role#assignee] or owner or viewer from parent", FgaDsl.print(viewer.withDirect(TypeRef("role", "assignee"))))
        assertEquals("[user, user:*, group#member] or viewer from parent", FgaDsl.print(viewer.without(Rewrite.Computed("owner"))!!))
        assertEquals("owner or editor", FgaDsl.print(Rewrite.Computed("owner").or(Rewrite.Computed("editor"))))
        assertEquals("[user] or owner", FgaDsl.print(Rewrite.Computed("owner").withDirect(TypeRef("user"))))
        val edited = model.withRelation("doc", RelationDefinition("auditor", Rewrite.Direct(listOf(TypeRef("user"))))).withoutRelation("doc", "blocked")
        assertNotNull(edited.type("doc")?.relation("auditor"))
        assertNull(edited.type("doc")?.relation("blocked"))
        assertEquals("doc#viewer: refers to blocked, which doc does not define", ModelRules.issues(edited).single().toString())
    }
}
