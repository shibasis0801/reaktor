------------------------ MODULE SessionSlotLifecycle ------------------------
EXTENDS Naturals, FiniteSets

CONSTANTS
    Identities,
    MaxEmissions,
    MaxMounts,
    DeferCloseWhileShown,
    CheckEpochOnCommit,
    CloseDiscardedInFinally

ASSUME
    /\ "NONE" \notin Identities
    /\ MaxEmissions \in Nat \ {0}
    /\ MaxMounts \in Nat \ {0}
    /\ DeferCloseWhileShown \in BOOLEAN
    /\ CheckEpochOnCommit \in BOOLEAN
    /\ CloseDiscardedInFinally \in BOOLEAN

VARIABLES
    epoch,
    targetIdentity,
    activeIdentity,
    activeEpoch,
    current,
    nextGraphId,
    constructed,
    ownerIdentity,
    ownerEpoch,
    building,
    shown,
    retired,
    closed,
    failed,
    slotClosed

vars == <<
    epoch, targetIdentity, activeIdentity, activeEpoch, current,
    nextGraphId, constructed, ownerIdentity, ownerEpoch,
    building, shown, retired, closed, failed, slotClosed
>>

Init ==
    /\ epoch = 0
    /\ targetIdentity = "NONE"
    /\ activeIdentity = "NONE"
    /\ activeEpoch = 0
    /\ current = 0
    /\ nextGraphId = 1
    /\ constructed = {}
    /\ ownerIdentity = [g \in 1..MaxEmissions |-> "NONE"]
    /\ ownerEpoch = [g \in 1..MaxEmissions |-> 0]
    /\ building = {}
    /\ shown = [g \in 1..MaxEmissions |-> 0]
    /\ retired = {}
    /\ closed = {}
    /\ failed = FALSE
    /\ slotClosed = FALSE

RetireCurrent(g, retSet, closedSet) ==
    IF g = 0 THEN
        /\ retired' = retSet
        /\ closed' = closedSet
    ELSE IF DeferCloseWhileShown /\ shown[g] > 0 THEN
        /\ retired' = retSet \cup {g}
        /\ closed' = closedSet
    ELSE
        /\ retired' = retSet
        /\ closed' = closedSet \cup {g}

EmitIdentity(id) ==
    /\ ~slotClosed
    /\ epoch < MaxEmissions
    /\ id \in Identities
    /\ id # targetIdentity \/ failed
    /\ LET nextEp == epoch + 1
           gid == nextGraphId
       IN /\ epoch' = nextEp
          /\ targetIdentity' = id
          /\ nextGraphId' = gid + 1
          /\ constructed' = constructed \cup {gid}
          /\ ownerIdentity' = [ownerIdentity EXCEPT ![gid] = id]
          /\ ownerEpoch' = [ownerEpoch EXCEPT ![gid] = nextEp]
          /\ building' = building \cup {[g |-> gid, ep |-> nextEp, id |-> id]}
          /\ failed' = FALSE
          /\ UNCHANGED <<activeIdentity, activeEpoch, current, shown, retired, closed, slotClosed>>

EmitSignOut ==
    /\ ~slotClosed
    /\ epoch < MaxEmissions
    /\ targetIdentity # "NONE"
    /\ LET nextEp == epoch + 1
       IN /\ epoch' = nextEp
          /\ targetIdentity' = "NONE"
          /\ activeIdentity' = "NONE"
          /\ activeEpoch' = nextEp
          /\ current' = 0
          /\ failed' = FALSE
          /\ RetireCurrent(current, retired, closed)
          /\ UNCHANGED <<nextGraphId, constructed, ownerIdentity, ownerEpoch, building, shown, slotClosed>>

PublishBuild(b) ==
    /\ ~slotClosed
    /\ CheckEpochOnCommit => b.ep = epoch
    /\ building' = building \ {b}
    /\ current' = b.g
    /\ activeIdentity' = b.id
    /\ activeEpoch' = b.ep
    /\ failed' = FALSE
    /\ RetireCurrent(current, retired, closed)
    /\ UNCHANGED <<epoch, targetIdentity, nextGraphId, constructed, ownerIdentity, ownerEpoch, shown, slotClosed>>

FailBuild(b) ==
    /\ ~slotClosed
    /\ b.ep = epoch
    /\ building' = building \ {b}
    /\ failed' = TRUE
    /\ closed' = IF CloseDiscardedInFinally THEN closed \cup {b.g} ELSE closed
    /\ UNCHANGED <<
        epoch, targetIdentity, activeIdentity, activeEpoch, current,
        nextGraphId, constructed, ownerIdentity, ownerEpoch,
        shown, retired, slotClosed
       >>

DiscardBuild(b) ==
    /\ slotClosed \/ b.ep < epoch
    /\ building' = building \ {b}
    /\ closed' = IF CloseDiscardedInFinally THEN closed \cup {b.g} ELSE closed
    /\ UNCHANGED <<
        epoch, targetIdentity, activeIdentity, activeEpoch, current,
        nextGraphId, constructed, ownerIdentity, ownerEpoch,
        shown, retired, failed, slotClosed
       >>

MountShown ==
    /\ ~slotClosed
    /\ current # 0
    /\ shown[current] < MaxMounts
    /\ shown' = [shown EXCEPT ![current] = @ + 1]
    /\ UNCHANGED <<
        epoch, targetIdentity, activeIdentity, activeEpoch, current,
        nextGraphId, constructed, ownerIdentity, ownerEpoch,
        building, retired, closed, failed, slotClosed
       >>

UnmountShown(g) ==
    /\ shown[g] > 0
    /\ shown' = [shown EXCEPT ![g] = @ - 1]
    /\ IF shown[g] = 1 /\ g \in retired THEN
           /\ retired' = retired \ {g}
           /\ closed' = closed \cup {g}
       ELSE
           /\ UNCHANGED <<retired, closed>>
    /\ UNCHANGED <<
        epoch, targetIdentity, activeIdentity, activeEpoch, current,
        nextGraphId, constructed, ownerIdentity, ownerEpoch,
        building, failed, slotClosed
       >>

CloseSlot ==
    /\ ~slotClosed
    /\ slotClosed' = TRUE
    /\ current' = 0
    /\ RetireCurrent(current, retired, closed)
    /\ UNCHANGED <<
        epoch, targetIdentity, activeIdentity, activeEpoch,
        nextGraphId, constructed, ownerIdentity, ownerEpoch,
        building, shown, failed
       >>

Quiescent ==
    /\ slotClosed
    /\ building = {}
    /\ \A g \in constructed : shown[g] = 0

Done ==
    /\ Quiescent
    /\ UNCHANGED vars

Next ==
    \/ \E id \in Identities : EmitIdentity(id)
    \/ EmitSignOut
    \/ \E b \in building : PublishBuild(b) \/ FailBuild(b) \/ DiscardBuild(b)
    \/ MountShown
    \/ \E g \in constructed : UnmountShown(g)
    \/ CloseSlot
    \/ Done

Spec == Init /\ [][Next]_vars

NeverCloseShownGraph ==
    \A g \in constructed : shown[g] > 0 => g \notin closed

ActiveGraphOpen ==
    current # 0 =>
        /\ current \in constructed
        /\ current \notin closed
        /\ current \notin retired

RetiredGraphsAwaitUnmount ==
    \A g \in retired : shown[g] > 0 /\ g \notin closed

NoActivationLeak ==
    \A g \in constructed :
        \/ (\E b \in building : b.g = g)
        \/ current = g
        \/ g \in retired
        \/ g \in closed

CurrentMatchesIdentity ==
    /\ (current # 0 => ownerIdentity[current] = activeIdentity)
    /\ (current # 0 /\ building = {} /\ ~failed /\ ~slotClosed) =>
        (activeIdentity = targetIdentity /\ activeEpoch = epoch)

AllClosedWhenQuiescent ==
    Quiescent => (closed = constructed /\ retired = {} /\ current = 0)

Safety ==
    /\ NeverCloseShownGraph
    /\ ActiveGraphOpen
    /\ RetiredGraphsAwaitUnmount
    /\ NoActivationLeak
    /\ CurrentMatchesIdentity
    /\ AllClosedWhenQuiescent
=============================================================================
