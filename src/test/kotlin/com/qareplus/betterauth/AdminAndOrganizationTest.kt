package com.qareplus.betterauth

import com.qareplus.betterauth.model.AuthResponse
import com.qareplus.betterauth.model.FullOrganization
import com.qareplus.betterauth.model.InvitationOutcome
import com.qareplus.betterauth.model.MembersPage
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.Organization
import com.qareplus.betterauth.model.PermissionCheck
import com.qareplus.betterauth.model.UsersPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminAndOrganizationTest {
    private val orgJson = """{"id":"o1","name":"Aga Khan","slug":"aga-khan","logo":null,"createdAt":"2026-01-02T03:04:05.000Z",
        "metadata":{"tier":"gold"},"latitude":-1.28,"longitude":36.82,"phoneNumber":"+254700","facilityType":"hospital"}"""
    private val memberJson = """{"id":"m1","organizationId":"o1","userId":"u1","role":"admin","createdAt":"2026-01-02T03:04:05.000Z",
        "user":{"id":"u1","name":"Ada","email":"ada@example.com"}}"""
    private val inviteJson = """{"id":"i1","organizationId":"o1","email":"x@y.co","role":"member","status":"pending",
        "inviterId":"u1","expiresAt":"2026-01-09T00:00:00.000Z","createdAt":"2026-01-02T03:04:05.000Z"}"""
    private val teamJson = """{"id":"t1","name":"ER","organizationId":"o1","createdAt":"2026-01-02T03:04:05.000Z","updatedAt":"2026-01-02T03:04:05.000Z"}"""

    // --- admin ---------------------------------------------------------------------------------------------------

    @Test
    fun `admin user management`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/get-user") -> json(USER_JSON) // bare user
                req.path.endsWith("/update-user") -> json(USER_JSON)
                else -> json("""{"user":$USER_JSON}""")
            }
        }
        val admin = server.client().admin
        assertEquals("u1", admin.getUser("u1").getOrThrow().id)
        assertEquals("u1", server.last.url.parameters["id"])
        assertEquals("u1", admin.setRole("u1", listOf("admin", "orgAdmin")).getOrThrow().id)
        assertEquals(JsonArray(listOf(JsonPrimitive("admin"), JsonPrimitive("orgAdmin"))), server.last.bodyJson()["role"])
        admin.setRole("u1", "admin")
        assertEquals(JsonPrimitive("admin"), server.last.bodyJson()["role"])
        admin.createUser("n@x.co", "N", password = "pw", roles = listOf("user"), data = buildJsonObject { put("countryId", "ke") })
        assertEquals(JsonPrimitive("ke"), (server.last.bodyJson()["data"] as JsonObject)["countryId"])
        assertEquals("u1", admin.updateUser("u1", buildJsonObject { put("name", "N") }).getOrThrow().id)
        assertEquals("u1", admin.banUser("u1", "spam", 3600).getOrThrow().id)
        assertEquals(JsonPrimitive(3600), server.last.bodyJson()["banExpiresIn"])
        admin.unbanUser("u1")
        assertEquals("/api/auth/admin/unban-user", server.last.path)
    }

    @Test
    fun `admin list users passes query and parses page`() = runTest {
        val server = TestServer { json("""{"users":[$USER_JSON],"total":42,"limit":10,"offset":20}""") }
        val page = server.client().admin.listUsers(searchValue = "ada", searchField = "email", limit = 10, offset = 20, sortBy = "createdAt", sortDirection = "desc")
            .success<UsersPage>()
        assertEquals(42, page.total)
        assertEquals(1, page.users.size)
        assertEquals("10", server.last.url.parameters["limit"])
        assertEquals("desc", server.last.url.parameters["sortDirection"])
        assertNull(server.last.url.parameters["filterField"])
    }

    @Test
    fun `admin sessions impersonation and permissions`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/list-user-sessions") -> json("""{"sessions":[$SESSION_JSON]}""")
                req.path.endsWith("/impersonate-user") -> json("""{"session":$SESSION_JSON,"user":$USER_JSON}""", headers = arrayOf("set-auth-token" to listOf("imp.sig")))
                req.path.endsWith("/has-permission") -> json("""{"error":null,"success":true}""")
                else -> json("""{"success":true}""")
            }
        }
        val auth = server.client()
        assertEquals(1, auth.admin.listUserSessions("u1").getOrThrow().size)
        val imp = auth.admin.impersonateUser("u2").success<AuthResponse>()
        assertEquals("tok-abc", imp.token)
        assertEquals("imp.sig", auth.currentToken())
        assertTrue(auth.admin.hasPermission(mapOf("user" to listOf("ban"))).success<PermissionCheck>().success)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("ban"))),
            (server.last.bodyJson()["permissions"] as JsonObject)["user"],
        )
        assertTrue(auth.admin.revokeUserSession("tok").success<OperationResult>().success)
        auth.admin.revokeUserSessions("u1")
        auth.admin.removeUser("u1")
        auth.admin.setUserPassword("u1", "new")
        assertEquals("/api/auth/admin/set-user-password", server.last.path)
        assertEquals(JsonPrimitive("new"), server.last.bodyJson()["newPassword"])
    }

    // --- organization --------------------------------------------------------------------------------------------

    @Test
    fun `organization create keeps server extra fields`() = runTest {
        val server = TestServer { json("""{"id":"o1","name":"Aga Khan","slug":"aga-khan","createdAt":"2026-01-02T03:04:05.000Z","members":[$memberJson],"latitude":-1.28}""") }
        val org = server.client().organization.create(
            name = "Aga Khan",
            slug = "aga-khan",
            additionalFields = buildJsonObject {
                put("latitude", -1.28)
                put("longitude", 36.82)
                put("phoneNumber", "+254700")
            },
        ).success<Organization>()
        val body = server.last.bodyJson()
        assertEquals(JsonPrimitive(36.82), body["longitude"])
        assertEquals(JsonPrimitive("+254700"), body["phoneNumber"])
        assertEquals(JsonPrimitive(-1.28), org.additionalFields["latitude"])
        assertTrue("members" !in org.additionalFields)
    }

    @Test
    fun `organization read endpoints`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/get-full-organization") ->
                    json("""{"id":"o1","name":"A","slug":"a","createdAt":"2026-01-02T03:04:05.000Z","members":[$memberJson],"invitations":[$inviteJson],"teams":[$teamJson]}""")
                req.path.endsWith("/get-organization") -> json("null")
                req.path.endsWith("/list-members") -> json("""{"members":[$memberJson],"total":7}""")
                req.path.endsWith("/get-active-member-role") -> json("""{"role":"owner"}""")
                req.path.endsWith("/get-active-member") -> json(memberJson)
                else -> json("[$orgJson]")
            }
        }
        val org = server.client().organization
        val full = org.getFullOrganization(organizationSlug = "a", membersLimit = 5).success<FullOrganization>()
        assertEquals("a", server.last.url.parameters["organizationSlug"])
        assertEquals("5", server.last.url.parameters["membersLimit"])
        assertEquals(1, full.members.size)
        assertEquals("ada@example.com", full.members.single().user?.email)
        assertEquals("pending", full.invitations.single().status)
        assertEquals("ER", full.teams.single().name)
        assertTrue(full.organization.additionalFields.isEmpty())

        assertNull(org.getOrganization("zzz").getOrThrow())
        val page = org.listMembers(limit = 5, organizationId = "o1").success<MembersPage>()
        assertEquals(7, page.total)
        assertEquals("o1", server.last.url.parameters["organizationId"])
        assertEquals("owner", org.getActiveMemberRole().getOrThrow())
        assertEquals("m1", org.getActiveMember().getOrThrow()?.id)
        val list = org.list().getOrThrow()
        assertEquals("hospital", (list.single().additionalFields["facilityType"] as JsonPrimitive).content)
        assertEquals("gold", ((list.single().metadata as JsonObject)["tier"] as JsonPrimitive).content)
    }

    @Test
    fun `organization set active and unset`() = runTest {
        val server = TestServer { json(orgJson) }
        val org = server.client().organization
        org.setActive(organizationId = "o1")
        assertEquals(JsonPrimitive("o1"), server.last.bodyJson()["organizationId"])
        org.unsetActive()
        assertEquals(JsonNull, server.last.bodyJson()["organizationId"])
        assertTrue("organizationId" in server.last.bodyJson())
    }

    @Test
    fun `organization update delete check slug`() = runTest {
        val server = TestServer { req -> if (req.path.endsWith("/check-slug")) json("""{"status":true}""") else json(orgJson) }
        val org = server.client().organization
        org.update(buildJsonObject { put("name", "New") }, "o1").success<Organization>()
        assertEquals(JsonPrimitive("o1"), server.last.bodyJson()["organizationId"])
        assertEquals(JsonPrimitive("New"), (server.last.bodyJson()["data"] as JsonObject)["name"])
        assertTrue(org.delete("o1").success<OperationResult>().success)
        assertTrue(org.checkSlug("free").success<OperationResult>().success)
    }

    @Test
    fun `invitations`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/accept-invitation") -> json("""{"invitation":$inviteJson,"member":$memberJson}""")
                req.path.endsWith("/cancel-invitation") -> json(inviteJson)
                req.path.endsWith("/list-invitations") || req.path.endsWith("/list-user-invitations") -> json("[$inviteJson]")
                req.path.endsWith("/get-invitation") -> json("""{"id":"i1","organizationId":"o1","email":"x@y.co","role":"member","status":"pending","inviterId":"u1","organizationName":"A","organizationSlug":"a","inviterEmail":"i@y.co"}""")
                else -> json(inviteJson)
            }
        }
        val org = server.client().organization
        org.inviteMember("x@y.co", "member", teamIds = listOf("t1", "t2"), resend = true).getOrThrow()
        assertEquals(JsonPrimitive("member"), server.last.bodyJson()["role"])
        assertEquals(JsonArray(listOf(JsonPrimitive("t1"), JsonPrimitive("t2"))), server.last.bodyJson()["teamId"])
        org.inviteMember("x@y.co", listOf("member", "admin"))
        assertEquals(JsonArray(listOf(JsonPrimitive("member"), JsonPrimitive("admin"))), server.last.bodyJson()["role"])

        val outcome = org.acceptInvitation("i1").success<InvitationOutcome>()
        assertEquals("m1", outcome.member?.id)
        assertEquals("i1", outcome.invitation?.id)
        assertEquals("i1", org.cancelInvitation("i1").getOrThrow()?.id)
        assertEquals("a", org.getInvitation("i1").getOrThrow().organizationSlug)
        assertEquals("i1", server.last.url.parameters["id"])
        assertEquals(1, org.listInvitations("o1").getOrThrow().size)
        assertEquals(1, org.listUserInvitations().getOrThrow().size)
        org.rejectInvitation("i1")
        assertEquals("/api/auth/organization/reject-invitation", server.last.path)
    }

    @Test
    fun `members`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/remove-member") -> json("""{"member":$memberJson}""")
                req.path.endsWith("/leave") -> json(memberJson)
                else -> json(memberJson)
            }
        }
        val org = server.client().organization
        assertEquals("m1", org.removeMember("x@y.co").getOrThrow().id)
        assertEquals(JsonPrimitive("x@y.co"), server.last.bodyJson()["memberIdOrEmail"])
        assertEquals("m1", org.updateMemberRole("m1", "admin").getOrThrow().id)
        assertEquals(JsonPrimitive("m1"), server.last.bodyJson()["memberId"])
        assertEquals("m1", org.leave("o1").getOrThrow()?.id)
    }

    @Test
    fun `teams`() = runTest {
        val server = TestServer { req ->
            when {
                req.path.endsWith("/remove-team") -> json("""{"message":"Team removed successfully."}""")
                req.path.endsWith("/remove-team-member") -> json("""{"message":"Team member removed successfully."}""")
                req.path.endsWith("/list-teams") || req.path.endsWith("/list-user-teams") -> json("[$teamJson]")
                req.path.endsWith("/list-team-members") -> json("""[{"id":"tm1","teamId":"t1","userId":"u1","createdAt":"2026-01-02T03:04:05.000Z"}]""")
                req.path.endsWith("/add-team-member") -> json("""{"id":"tm1","teamId":"t1","userId":"u1"}""")
                req.path.endsWith("/set-active-team") -> json("null")
                else -> json(teamJson)
            }
        }
        val org = server.client().organization
        assertEquals("t1", org.createTeam("ER").getOrThrow().id)
        assertEquals("t1", org.updateTeam("t1", name = "ER2").getOrThrow().id)
        assertEquals(JsonPrimitive("ER2"), (server.last.bodyJson()["data"] as JsonObject)["name"])
        assertEquals(1, org.listTeams("o1").getOrThrow().size)
        assertEquals(1, org.listUserTeams().getOrThrow().size)
        assertEquals("u1", org.listTeamMembers("t1").getOrThrow().single().userId)
        assertEquals("tm1", org.addTeamMember("t1", "u1").getOrThrow().id)
        assertTrue(org.removeTeamMember("t1", "u1").success<OperationResult>().success)
        assertTrue(org.removeTeam("t1").success<OperationResult>().success)
        assertNull(org.setActiveTeam(null).getOrThrow())
        assertEquals(JsonNull, server.last.bodyJson()["teamId"])
    }

    @Test
    fun `dynamic roles and permission check`() = runTest {
        val role = """{"id":"r1","organizationId":"o1","role":"nurse","permission":{"booking":["manage"]},"createdAt":"2026-01-02T03:04:05.000Z"}"""
        val server = TestServer { req ->
            when {
                req.path.endsWith("/create-role") || req.path.endsWith("/update-role") -> json("""{"success":true,"roleData":$role}""")
                req.path.endsWith("/list-roles") -> json("[$role]")
                req.path.endsWith("/get-role") -> json(role)
                req.path.endsWith("/has-permission") -> json("""{"error":null,"success":false}""")
                else -> json("""{"success":true}""")
            }
        }
        val org = server.client().organization
        val created = org.createRole("nurse", mapOf("booking" to listOf("manage"))).getOrThrow()
        assertEquals("r1", created?.id)
        assertEquals(JsonPrimitive("nurse"), server.last.bodyJson()["role"])
        assertEquals(1, org.listRoles().getOrThrow().size)
        assertEquals("nurse", org.getRole(roleName = "nurse").getOrThrow().role)
        assertEquals("nurse", server.last.url.parameters["roleName"])
        assertEquals("r1", org.updateRole(roleName = "nurse", permission = mapOf("booking" to listOf("manage", "triage"))).getOrThrow()?.id)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("manage"), JsonPrimitive("triage"))),
            ((server.last.bodyJson()["data"] as JsonObject)["permission"] as JsonObject)["booking"],
        )
        assertTrue(org.deleteRole(roleId = "r1").success<OperationResult>().success)
        assertEquals(false, org.hasPermission(mapOf("billing" to listOf("manage"))).success<PermissionCheck>().success)
    }
}
