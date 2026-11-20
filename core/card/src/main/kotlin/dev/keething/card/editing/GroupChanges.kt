package dev.keething.card.editing

import dev.keething.card.editing.NameFolding.fold
import dev.keething.domain.vault.Entry
import dev.keething.domain.vault.EntryChanges
import dev.keething.domain.vault.GroupDestination
import dev.keething.domain.vault.NewGroup
import dev.keething.domain.vault.Vault
import dev.keething.domain.vault.VaultEdit
import dev.keething.domain.vault.VaultGroup
import java.text.Collator

/**
 * What Groups & tags may change of a database's groups (keething-spec groups.manage; the Mac's `GroupsAndTagsModel`,
 * plans/android-groups.md G5), asked before anything is saved; the checked save asks the file again. Groups go by id,
 * so of two groups of one name in one parent each is itself (the Mac does not offer such a group). A name is taken
 * case and accents aside, as a path's levels are matched, also when the recycle bin or the templates group has it.
 */
public class GroupChanges(private val tree: GroupTree) {
    /** A name typed in the dialog, trimmed. */
    public sealed interface Name {
        public data class Ok(val name: String) : Name

        /** A rename to the name it has: the dialog closes without writing. */
        public data object Unchanged : Name

        public data object Empty : Name

        /** [parent] has [taken] already. */
        public data class Taken(val parent: VaultGroup, val taken: VaultGroup) : Name

        /** [parent] has the recycle bin or the templates group of that name, not shown among the groups. */
        public data class TakenByHidden(val parent: VaultGroup, val hidden: GroupTree.Hidden) : Name
    }

    /** Why a group may not go into a target (groups.manage AC5); the recycle bin is never one (not in the tree). */
    public sealed interface MoveRefusal {
        public data object Itself : MoveRefusal

        /** The target is inside the group. */
        public data object Inside : MoveRefusal

        /** The target is the group's parent. */
        public data object HereNow : MoveRefusal

        /** [target] has [taken], a group of its name, already. */
        public data class NameTaken(val target: VaultGroup, val taken: VaultGroup) : MoveRefusal

        /** [target] has the recycle bin or the templates group of its name. */
        public data class NameTakenByHidden(val target: VaultGroup, val hidden: GroupTree.Hidden) : MoveRefusal
    }

    /** Renamed, moved, deleted: any group but the root group (groups.manage AC7). */
    public fun canChange(groupId: String): Boolean = tree.group(groupId)?.isRoot == false

    /** Only an empty group: no entries (the Mac app's settings entry counted) and no groups, the bin and the templates group counted (AC6). */
    public fun canDelete(groupId: String): Boolean =
        canChange(groupId) && tree.node(groupId)?.let { it.total == 0 && it.subgroups == 0 } == true && tree.hiddenIn(groupId).isEmpty()

    /** A new name for [groupId] (AC1); null when it cannot be renamed. */
    public fun rename(groupId: String, typed: String): Name? {
        val group = tree.group(groupId)?.takeIf { canChange(groupId) } ?: return null
        val name = typed.trim()
        return when {
            name.isEmpty() -> Name.Empty
            name == group.name -> Name.Unchanged
            else -> check(name, tree.parentOf(groupId) ?: return null, except = groupId)
        }
    }

    /** The name of a new group in [parentId] (AC3); null when that group is gone. */
    public fun create(parentId: String, typed: String): Name? {
        val parent = tree.group(parentId) ?: return null
        val name = typed.trim()
        return if (name.isEmpty()) Name.Empty else check(name, parent, except = null)
    }

    /** Why [groupId] may not go into [targetId]; null when it may (both there and the group changeable: [canMove]). */
    public fun moveRefusal(groupId: String, targetId: String): MoveRefusal? {
        val group = tree.group(groupId) ?: return null
        val target = tree.group(targetId) ?: return null
        return when {
            groupId == targetId -> MoveRefusal.Itself
            tree.isBelow(targetId, groupId) -> MoveRefusal.Inside
            tree.parentOf(groupId)?.id == targetId -> MoveRefusal.HereNow
            else -> namesake(group.name, target, except = groupId)?.let { MoveRefusal.NameTaken(target, it) }
                ?: tree.hiddenNamed(target.id, group.name)?.let { MoveRefusal.NameTakenByHidden(target, it) }
        }
    }

    public fun canMove(groupId: String, targetId: String): Boolean =
        canChange(groupId) && tree.group(targetId) != null && moveRefusal(groupId, targetId) == null

    private fun check(name: String, parent: VaultGroup, except: String?): Name =
        namesake(name, parent, except)?.let { Name.Taken(parent, it) }
            ?: tree.hiddenNamed(parent.id, name)?.let { Name.TakenByHidden(parent, it) }
            ?: Name.Ok(name)

    private fun namesake(name: String, parent: VaultGroup, except: String?): VaultGroup? {
        val folded = fold(name)
        return tree.children(parent.id).firstOrNull { it.id != except && fold(it.name) == folded }
    }

    public companion object {
        /** The groups of [vault] outside the bin, the Mac app's settings entry among what they hold. */
        public fun of(vault: Vault, collator: Collator = Collator.getInstance()): GroupChanges =
            GroupChanges(GroupTree(vault.groups, vault.entries + listOfNotNull(vault.settings), collator, GroupTree.hidden(vault)))
    }
}

/** The card's ⋮ › Move to group… (plans/android-groups.md G6; keething-spec groups.entry-group AC2): the move alone. */
public object EntryMove {
    /** The edit that moves [entry] where [choice] says, its new groups' ids from [newId]; null when it is there already. */
    public fun edit(entry: Entry, choice: GroupChoice, newId: () -> String = Entry::newId): VaultEdit.Update? {
        if (choice == GroupChoice(entry.groupId)) return null
        val destination = GroupDestination(choice.groupId, choice.newNames.map { NewGroup(newId(), it) })
        return VaultEdit.Update(entry.id, entry.lastModified, EntryChanges(group = destination))
    }
}
