@file:UseSerializers(InstantIsoSerializer::class)

package com.timothymugo.betterauth.client.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An organization.
 *
 * Fields your server adds through `organization.additionalFields` (for example `latitude` or `phoneNumber`) are not
 * typed here; they are kept verbatim in [additionalFields].
 */
@Serializable(with = OrganizationSerializer::class)
public data class Organization(
    val id: String,
    val name: String = "",
    val slug: String = "",
    val logo: String? = null,
    val createdAt: Instant? = null,
    /** Free-form metadata: usually a JSON object, sometimes a string. */
    val metadata: JsonElement? = null,
    val additionalFields: JsonObject = EmptyObject,
)

internal object OrganizationSerializer : JsonObjectSerializer<Organization>("com.timothymugo.betterauth.client.Organization") {
    // `members`, `invitations` and `teams` belong to FullOrganization / create responses, not to the organization.
    private val known = setOf("id", "name", "slug", "logo", "createdAt", "metadata", "members", "invitations", "teams")

    override fun fromJson(obj: JsonObject): Organization = Organization(
        id = obj.str("id") ?: error("Organization.id missing"),
        name = obj.str("name") ?: "",
        slug = obj.str("slug") ?: "",
        logo = obj.str("logo"),
        createdAt = obj.instant("createdAt"),
        metadata = obj["metadata"]?.takeIf { it !is kotlinx.serialization.json.JsonNull },
        additionalFields = obj.without(known),
    )

    override fun toJson(value: Organization): JsonObject = jsonBody {
        put("id", value.id)
        put("name", value.name)
        put("slug", value.slug)
        put("logo", value.logo)
        put("createdAt", value.createdAt)
        put("metadata", value.metadata)
        putAll(value.additionalFields)
    }
}

/** The slice of a user embedded in a [Member]. */
@Serializable
public data class MemberUser(
    val id: String,
    val name: String = "",
    val email: String = "",
    val image: String? = null,
)

/** [role] holds several roles comma-separated, as the server stores them (e.g. `"admin,owner"`). */
@Serializable
public data class Member(
    val id: String,
    val organizationId: String = "",
    val userId: String = "",
    val role: String = "",
    val createdAt: Instant? = null,
    val teamId: String? = null,
    val user: MemberUser? = null,
)

/**
 * An invitation. The `organizationName`, `organizationSlug` and `inviterEmail` fields are only filled by
 * `get-invitation` and `list-user-invitations`.
 */
@Serializable
public data class Invitation(
    val id: String,
    val organizationId: String = "",
    val email: String = "",
    val role: String = "",
    val status: String = "",
    val teamId: String? = null,
    val inviterId: String = "",
    val expiresAt: Instant? = null,
    val createdAt: Instant? = null,
    val organizationName: String? = null,
    val organizationSlug: String? = null,
    val inviterEmail: String? = null,
)

@Serializable
public data class Team(
    val id: String,
    val name: String = "",
    val organizationId: String = "",
    val memberCount: Int? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

@Serializable
public data class TeamMember(
    val id: String,
    val teamId: String = "",
    val userId: String = "",
    val createdAt: Instant? = null,
)

/** `get-full-organization`: the organization plus its members, invitations and teams. */
public data class FullOrganization(
    val organization: Organization,
    val members: List<Member> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    val teams: List<Team> = emptyList(),
)

/** One page of `list-members`. */
public data class MembersPage(val members: List<Member>, val total: Long)

/** Result of accepting or rejecting an invitation. [member] is only set on accept. */
public data class InvitationOutcome(val invitation: Invitation?, val member: Member?)

/** A dynamic access-control role. [permission] maps a resource to its allowed actions. */
@Serializable
public data class OrganizationRole(
    val id: String? = null,
    val organizationId: String? = null,
    val role: String = "",
    /** Usually a JSON object `{resource: [actions]}`; the server stores it as a JSON string. */
    val permission: JsonElement? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

/** Result of `has-permission`. */
public data class PermissionCheck(val success: Boolean, val error: String? = null)

/** One page of `admin/list-users`. */
public data class UsersPage(val users: List<User>, val total: Long, val limit: Long? = null, val offset: Long? = null)

/** `{resource: [actions]}`. */
public typealias Permissions = Map<String, List<String>>
