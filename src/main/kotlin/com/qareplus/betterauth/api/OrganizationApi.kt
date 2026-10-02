package com.qareplus.betterauth.api

import com.qareplus.betterauth.http.Transport
import com.qareplus.betterauth.model.FullOrganization
import com.qareplus.betterauth.model.Invitation
import com.qareplus.betterauth.model.InvitationOutcome
import com.qareplus.betterauth.model.Member
import com.qareplus.betterauth.model.MembersPage
import com.qareplus.betterauth.model.OperationResult
import com.qareplus.betterauth.model.Organization
import com.qareplus.betterauth.model.OrganizationRole
import com.qareplus.betterauth.model.PermissionCheck
import com.qareplus.betterauth.model.Permissions
import com.qareplus.betterauth.model.Team
import com.qareplus.betterauth.model.TeamMember
import com.qareplus.betterauth.model.arr
import com.qareplus.betterauth.model.jsonBody
import com.qareplus.betterauth.model.long
import com.qareplus.betterauth.model.obj
import com.qareplus.betterauth.model.str
import com.qareplus.betterauth.result.BetterAuthResult
import io.ktor.http.HttpMethod
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `client.organization`: organizations, members, invitations, teams and dynamic roles.
 *
 * Most calls act on the session's *active* organization unless you pass `organizationId`.
 */
public class OrganizationApi internal constructor(private val t: Transport) {

    // --- organizations ------------------------------------------------------------------------------------------

    /**
     * Creates an organization and makes it active unless [keepCurrentActiveOrganization] is set.
     * Put fields from your server's `organization.additionalFields` in [additionalFields].
     */
    public suspend fun create(
        name: String,
        slug: String,
        logo: String? = null,
        metadata: JsonObject? = null,
        keepCurrentActiveOrganization: Boolean? = null,
        additionalFields: JsonObject? = null,
    ): BetterAuthResult<Organization> = t.post(
        "/organization/create",
        jsonBody {
            putAll(additionalFields)
            put("name", name)
            put("slug", slug)
            put("logo", logo)
            put("metadata", metadata)
            put("keepCurrentActiveOrganization", keepCurrentActiveOrganization)
        },
    ) { it.decodeAs<Organization>() }

    /** Updates an organization. [data] carries the changed fields (`name`, `slug`, `logo`, `metadata`, extras). */
    public suspend fun update(data: JsonObject, organizationId: String? = null): BetterAuthResult<Organization> = t.post(
        "/organization/update",
        jsonBody {
            put("data", data)
            put("organizationId", organizationId)
        },
    ) { it.decodeAs<Organization>() }

    public suspend fun delete(organizationId: String): BetterAuthResult<OperationResult> =
        t.post("/organization/delete", jsonBody { put("organizationId", organizationId) }) { OperationResult(true) }

    /** Sets the active organization (by id or slug). Returns `null` if the server reports none. */
    public suspend fun setActive(organizationId: String? = null, organizationSlug: String? = null): BetterAuthResult<Organization?> =
        t.post(
            "/organization/set-active",
            jsonBody {
                put("organizationId", organizationId)
                put("organizationSlug", organizationSlug)
            },
        ) { it.toOrganizationOrNull() }

    /** Clears the active organization. */
    public suspend fun unsetActive(): BetterAuthResult<Organization?> = t.post(
        "/organization/set-active",
        jsonBody { put("organizationId", JsonNull) },
    ) { it.toOrganizationOrNull() }

    public suspend fun getOrganization(organizationId: String? = null, organizationSlug: String? = null): BetterAuthResult<Organization?> =
        t.get(
            "/organization/get-organization",
            query = mapOf("organizationId" to organizationId, "organizationSlug" to organizationSlug),
        ) { it.toOrganizationOrNull() }

    public suspend fun getFullOrganization(
        organizationId: String? = null,
        organizationSlug: String? = null,
        membersLimit: Int? = null,
    ): BetterAuthResult<FullOrganization?> = t.get(
        "/organization/get-full-organization",
        query = mapOf(
            "organizationId" to organizationId,
            "organizationSlug" to organizationSlug,
            "membersLimit" to membersLimit?.toString(),
        ),
    ) { element ->
        if (element is JsonNull) return@get null
        val o = element.asObject()
        FullOrganization(
            organization = element.decodeAs<Organization>(),
            members = o.arr("members").orEmpty().map { it.decodeAs<Member>() },
            invitations = o.arr("invitations").orEmpty().map { it.decodeAs<Invitation>() },
            teams = o.arr("teams").orEmpty().map { it.decodeAs<Team>() },
        )
    }

    /** Organizations the user belongs to. */
    public suspend fun list(): BetterAuthResult<List<Organization>> =
        t.call(HttpMethod.Get, "/organization/list", ListSerializer(Organization.serializer()))

    /** Succeeds when [slug] is free; a taken slug comes back as an API failure. */
    public suspend fun checkSlug(slug: String): BetterAuthResult<OperationResult> =
        t.postForResult("/organization/check-slug", jsonBody { put("slug", slug) })

    // --- invitations --------------------------------------------------------------------------------------------

    public suspend fun inviteMember(
        email: String,
        role: String,
        organizationId: String? = null,
        resend: Boolean? = null,
        teamIds: List<String>? = null,
    ): BetterAuthResult<Invitation> = inviteMember(email, listOf(role), organizationId, resend, teamIds)

    public suspend fun inviteMember(
        email: String,
        roles: List<String>,
        organizationId: String? = null,
        resend: Boolean? = null,
        teamIds: List<String>? = null,
    ): BetterAuthResult<Invitation> = t.post(
        "/organization/invite-member",
        jsonBody {
            put("email", email)
            put("role", rolesToJson(roles))
            put("organizationId", organizationId)
            put("resend", resend)
            put("teamId", teamIds?.let(::rolesToJson))
        },
    ) { it.decodeAs<Invitation>() }

    public suspend fun cancelInvitation(invitationId: String): BetterAuthResult<Invitation?> =
        t.post("/organization/cancel-invitation", jsonBody { put("invitationId", invitationId) }) {
            it.asObject().takeIf { o -> o.containsKey("id") }?.decodeAs<Invitation>()
        }

    public suspend fun acceptInvitation(invitationId: String): BetterAuthResult<InvitationOutcome> =
        t.post("/organization/accept-invitation", jsonBody { put("invitationId", invitationId) }) { it.toInvitationOutcome() }

    public suspend fun rejectInvitation(invitationId: String): BetterAuthResult<InvitationOutcome> =
        t.post("/organization/reject-invitation", jsonBody { put("invitationId", invitationId) }) { it.toInvitationOutcome() }

    public suspend fun getInvitation(id: String): BetterAuthResult<Invitation> =
        t.call(HttpMethod.Get, "/organization/get-invitation", Invitation.serializer(), query = mapOf("id" to id))

    public suspend fun listInvitations(organizationId: String? = null): BetterAuthResult<List<Invitation>> =
        t.call(
            HttpMethod.Get,
            "/organization/list-invitations",
            ListSerializer(Invitation.serializer()),
            query = mapOf("organizationId" to organizationId),
        )

    /** Pending invitations addressed to the signed-in user. */
    public suspend fun listUserInvitations(): BetterAuthResult<List<Invitation>> =
        t.call(HttpMethod.Get, "/organization/list-user-invitations", ListSerializer(Invitation.serializer()))

    // --- members ------------------------------------------------------------------------------------------------

    public suspend fun getActiveMember(): BetterAuthResult<Member?> = t.get("/organization/get-active-member") {
        if (it is JsonNull) null else it.decodeAs<Member>()
    }

    /** The role string of the active member, or of [userId] if given. */
    public suspend fun getActiveMemberRole(
        userId: String? = null,
        organizationId: String? = null,
        organizationSlug: String? = null,
    ): BetterAuthResult<String?> = t.get(
        "/organization/get-active-member-role",
        query = mapOf("userId" to userId, "organizationId" to organizationId, "organizationSlug" to organizationSlug),
    ) { it.asObject().str("role") }

    public suspend fun listMembers(
        organizationId: String? = null,
        organizationSlug: String? = null,
        limit: Int? = null,
        offset: Int? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        filterField: String? = null,
        filterValue: String? = null,
        filterOperator: String? = null,
    ): BetterAuthResult<MembersPage> = t.get(
        "/organization/list-members",
        query = mapOf(
            "organizationId" to organizationId,
            "organizationSlug" to organizationSlug,
            "limit" to limit?.toString(),
            "offset" to offset?.toString(),
            "sortBy" to sortBy,
            "sortDirection" to sortDirection,
            "filterField" to filterField,
            "filterValue" to filterValue,
            "filterOperator" to filterOperator,
        ),
    ) { element ->
        val o = element.asObject()
        MembersPage(o.arr("members").orEmpty().map { it.decodeAs<Member>() }, o.long("total") ?: 0)
    }

    public suspend fun removeMember(memberIdOrEmail: String, organizationId: String? = null): BetterAuthResult<Member> =
        t.post(
            "/organization/remove-member",
            jsonBody {
                put("memberIdOrEmail", memberIdOrEmail)
                put("organizationId", organizationId)
            },
        ) { element -> (element.asObject().obj("member") ?: error("`member` missing in response")).decodeAs<Member>() }

    public suspend fun updateMemberRole(memberId: String, role: String, organizationId: String? = null): BetterAuthResult<Member> =
        updateMemberRole(memberId, listOf(role), organizationId)

    public suspend fun updateMemberRole(memberId: String, roles: List<String>, organizationId: String? = null): BetterAuthResult<Member> =
        t.post(
            "/organization/update-member-role",
            jsonBody {
                put("memberId", memberId)
                put("role", rolesToJson(roles))
                put("organizationId", organizationId)
            },
        ) { element ->
            val o = element.asObject()
            (o.obj("member") ?: o).decodeAs<Member>()
        }

    /** Leaves [organizationId]. */
    public suspend fun leave(organizationId: String): BetterAuthResult<Member?> =
        t.post("/organization/leave", jsonBody { put("organizationId", organizationId) }) {
            it.asObject().takeIf { o -> o.containsKey("id") }?.decodeAs<Member>()
        }

    // --- teams --------------------------------------------------------------------------------------------------

    public suspend fun createTeam(name: String, organizationId: String? = null): BetterAuthResult<Team> = t.post(
        "/organization/create-team",
        jsonBody {
            put("name", name)
            put("organizationId", organizationId)
        },
    ) { it.decodeAs<Team>() }

    public suspend fun listTeams(organizationId: String? = null): BetterAuthResult<List<Team>> = t.call(
        HttpMethod.Get,
        "/organization/list-teams",
        ListSerializer(Team.serializer()),
        query = mapOf("organizationId" to organizationId),
    )

    public suspend fun updateTeam(teamId: String, name: String? = null, organizationId: String? = null): BetterAuthResult<Team> =
        t.post(
            "/organization/update-team",
            jsonBody {
                put("teamId", teamId)
                put("data", jsonBody { put("name", name) })
                put("organizationId", organizationId)
            },
        ) { it.decodeAs<Team>() }

    public suspend fun removeTeam(teamId: String, organizationId: String? = null): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/organization/remove-team",
            jsonBody {
                put("teamId", teamId)
                put("organizationId", organizationId)
            },
        )

    /** Sets the active team; pass `null` to clear it. */
    public suspend fun setActiveTeam(teamId: String?): BetterAuthResult<Team?> = t.post(
        "/organization/set-active-team",
        jsonBody { put("teamId", teamId?.let { JsonPrimitive(it) } ?: JsonNull) },
    ) { if (it is JsonNull) null else it.decodeAs<Team>() }

    /** Teams of [userId] (default: the signed-in user), optionally within one organization. */
    public suspend fun listUserTeams(userId: String? = null, organizationId: String? = null): BetterAuthResult<List<Team>> =
        t.call(
            HttpMethod.Get,
            "/organization/list-user-teams",
            ListSerializer(Team.serializer()),
            query = mapOf("userId" to userId, "organizationId" to organizationId),
        )

    /** Members of [teamId], or of the active team if omitted. */
    public suspend fun listTeamMembers(teamId: String? = null): BetterAuthResult<List<TeamMember>> =
        t.call(
            HttpMethod.Get,
            "/organization/list-team-members",
            ListSerializer(TeamMember.serializer()),
            query = mapOf("teamId" to teamId),
        )

    public suspend fun addTeamMember(teamId: String, userId: String, organizationId: String? = null): BetterAuthResult<TeamMember> =
        t.post(
            "/organization/add-team-member",
            jsonBody {
                put("teamId", teamId)
                put("userId", userId)
                put("organizationId", organizationId)
            },
        ) { it.decodeAs<TeamMember>() }

    public suspend fun removeTeamMember(teamId: String, userId: String, organizationId: String? = null): BetterAuthResult<OperationResult> =
        t.postForResult(
            "/organization/remove-team-member",
            jsonBody {
                put("teamId", teamId)
                put("userId", userId)
                put("organizationId", organizationId)
            },
        )

    // --- dynamic access control ---------------------------------------------------------------------------------

    /** Creates a role (needs `dynamicAccessControl` on the server). [permission] maps resources to actions. */
    public suspend fun createRole(
        role: String,
        permission: Permissions,
        organizationId: String? = null,
        additionalFields: JsonObject? = null,
    ): BetterAuthResult<OrganizationRole?> = t.post(
        "/organization/create-role",
        jsonBody {
            put("role", role)
            put("permission", permission.toJson())
            put("organizationId", organizationId)
            put("additionalFields", additionalFields)
        },
    ) { it.toRoleOrNull() }

    /** Identify the role by [roleName] or [roleId]. */
    public suspend fun deleteRole(
        roleName: String? = null,
        roleId: String? = null,
        organizationId: String? = null,
    ): BetterAuthResult<OperationResult> = t.postForResult(
        "/organization/delete-role",
        jsonBody {
            put("roleName", roleName)
            put("roleId", roleId)
            put("organizationId", organizationId)
        },
    )

    public suspend fun listRoles(organizationId: String? = null): BetterAuthResult<List<OrganizationRole>> =
        t.call(
            HttpMethod.Get,
            "/organization/list-roles",
            ListSerializer(OrganizationRole.serializer()),
            query = mapOf("organizationId" to organizationId),
        )

    public suspend fun getRole(
        roleName: String? = null,
        roleId: String? = null,
        organizationId: String? = null,
    ): BetterAuthResult<OrganizationRole> = t.call(
        HttpMethod.Get,
        "/organization/get-role",
        OrganizationRole.serializer(),
        query = mapOf("roleName" to roleName, "roleId" to roleId, "organizationId" to organizationId),
    )

    /** Identify the role by [roleName] or [roleId]; set [newRoleName] and/or [permission] to change it. */
    public suspend fun updateRole(
        roleName: String? = null,
        roleId: String? = null,
        newRoleName: String? = null,
        permission: Permissions? = null,
        organizationId: String? = null,
    ): BetterAuthResult<OrganizationRole?> = t.post(
        "/organization/update-role",
        jsonBody {
            put("roleName", roleName)
            put("roleId", roleId)
            put("organizationId", organizationId)
            put(
                "data",
                jsonBody {
                    put("roleName", newRoleName)
                    put("permission", permission?.toJson())
                },
            )
        },
    ) { it.toRoleOrNull() }

    /** Checks whether the signed-in member holds [permissions]. */
    public suspend fun hasPermission(permissions: Permissions, organizationId: String? = null): BetterAuthResult<PermissionCheck> =
        t.post(
            "/organization/has-permission",
            jsonBody {
                put("permissions", permissions.toJson())
                put("organizationId", organizationId)
            },
        ) { it.toPermissionCheck() }
}

private fun JsonElement.toOrganizationOrNull(): Organization? =
    if (this is JsonNull) null else decodeAs<Organization>()

private fun JsonElement.toInvitationOutcome(): InvitationOutcome {
    val o = asObject()
    return InvitationOutcome(
        invitation = o.obj("invitation")?.decodeAs<Invitation>(),
        member = o.obj("member")?.decodeAs<Member>(),
    )
}

/** create/update-role answer `{success, roleData}`; get/list answer the role itself. */
private fun JsonElement.toRoleOrNull(): OrganizationRole? {
    val o = asObject()
    val role = o.obj("roleData") ?: o.takeIf { it.containsKey("role") } ?: return null
    return role.decodeAs<OrganizationRole>()
}
