package cc.openxiot.matrix.api.space;

import cc.openxiot.matrix.api.member.AccountUserLookup;
import cc.openxiot.matrix.db.space.SpaceOwnerRole;
import cc.openxiot.matrix.db.space.SpaceEntity;
import cc.openxiot.matrix.db.space.SpaceOwner;
import cc.openxiot.matrix.db.space.SpaceOwnerType;
import cc.openxiot.matrix.db.space.SpaceRepository;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpaceServiceTest {

    @Mock
    SpaceRepository spaceRepository;

    @Mock
    AccountUserLookup accountUserLookup;

    @InjectMocks
    SpaceService service;

    private static SpaceOwner orgOwner(String orgId) {
        return new SpaceOwner(orgId, SpaceOwnerType.ORGANIZATION, SpaceOwnerRole.ADMIN);
    }

    private static SpaceOwner userOwner(String userId, String role) {
        return new SpaceOwner(userId, SpaceOwnerType.USER, role);
    }

    @Test
    void noAccess_unknownSpace_returnsTrue() {
        when(spaceRepository.findById("unknown")).thenReturn(Uni.createFrom().nullItem());

        Boolean result = service.noAccess("unknown").await().indefinitely();

        assertTrue(result);
    }

    @Test
    void noAccess_existingSpace_returnsFalse() {
        SpaceEntity space = new SpaceEntity();
        when(spaceRepository.findById("sid")).thenReturn(Uni.createFrom().item(space));

        Boolean result = service.noAccess("sid").await().indefinitely();

        assertFalse(result);
    }

    @Test
    void getDescendants_returnsRootAndChildren() {
        SpaceEntity root = new SpaceEntity();
        SpaceEntity child = new SpaceEntity();
        when(spaceRepository.findById("root-id")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.findByAncestor("root-id"))
                .thenReturn(Uni.createFrom().item(List.of(child)));

        List<SpaceEntity> result = service.getDescendants("root-id").await().indefinitely();

        assertEquals(2, result.size());
        assertSame(root, result.get(0));
        assertSame(child, result.get(1));
    }

    @Test
    void getDescendants_unknownRoot_returnsEmpty() {
        when(spaceRepository.findById("unknown")).thenReturn(Uni.createFrom().nullItem());

        List<SpaceEntity> result = service.getDescendants("unknown").await().indefinitely();

        assertTrue(result.isEmpty());
    }

    @Test
    void delete_withoutChildren_succeeds() {
        when(spaceRepository.findByParentId("sid")).thenReturn(Uni.createFrom().item(List.of()));
        when(spaceRepository.deleteById(any(String.class)))
                .thenReturn(Uni.createFrom().item(true));

        service.delete("sid").await().indefinitely();

        verify(spaceRepository).deleteById(anyString());
    }

    @Test
    void delete_withChildren_throws() {
        when(spaceRepository.findByParentId("sid"))
                .thenReturn(Uni.createFrom().item(List.of(new SpaceEntity())));

        assertThrows(IllegalArgumentException.class,
                () -> service.delete("sid").await().indefinitely());
        verify(spaceRepository, never()).deleteById(anyString());
    }

    @Test
    void create_rootSpace_setsLevelAndAncestors() {
        SpaceEntity space = new SpaceEntity();
        space.owners = List.of(orgOwner("org-1"));
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.create(space).await().indefinitely();

        assertEquals(0, result.level);
        assertTrue(result.ancestors.isEmpty());
        assertNull(result.parentId);
        assertEquals("org-1", result.owners.get(0).id);
    }

    @Test
    void create_rootSpaceMissingOwners_throws() {
        SpaceEntity space = new SpaceEntity();
        assertThrows(IllegalArgumentException.class,
                () -> service.create(space).await().indefinitely());
    }

    @Test
    void create_childSpace_calculatesLevelAndAncestors() {
        SpaceEntity parent = new SpaceEntity();
        parent.level = 2;
        parent.ancestors = List.of("root-id", "parent1-id");
        parent.owners = List.of(orgOwner("org-1"));
        when(spaceRepository.findById("parent-id")).thenReturn(Uni.createFrom().item(parent));

        SpaceEntity space = new SpaceEntity();
        space.parentId = "parent-id";
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.create(space).await().indefinitely();

        assertEquals(3, result.level);
        assertEquals(List.of("root-id", "parent1-id", "parent-id"), result.ancestors);
        assertEquals("org-1", result.owners.get(0).id);
    }

    @Test
    void create_childSpaceMissingParent_throws() {
        when(spaceRepository.findById("parent-id")).thenReturn(Uni.createFrom().nullItem());

        SpaceEntity space = new SpaceEntity();
        space.parentId = "parent-id";

        assertThrows(IllegalArgumentException.class,
                () -> service.create(space).await().indefinitely());
    }

    @Test
    void createWithOwner_user_resolvesNameAndCreates() {
        when(accountUserLookup.userName("u1")).thenReturn(Uni.createFrom().item("user-one"));
        SpaceEntity space = new SpaceEntity();
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.createWithOwner(space, userOwner("u1", SpaceOwnerRole.ADMIN)).await().indefinitely();

        assertEquals(0, result.level);
        assertEquals(1, result.owners.size());
        SpaceOwner owner = result.owners.get(0);
        assertEquals("u1", owner.id);
        assertEquals(SpaceOwnerType.USER, owner.type);
        assertEquals(SpaceOwnerRole.ADMIN, owner.role);
        assertEquals("user-one", owner.name);
    }

    @Test
    void createWithOwner_org_resolvesNameAndCreates() {
        when(accountUserLookup.orgName("org-1")).thenReturn(Uni.createFrom().item("Acme"));
        SpaceEntity space = new SpaceEntity();
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.createWithOwner(space, orgOwner("org-1")).await().indefinitely();

        assertEquals("Acme", result.owners.get(0).name);
        assertEquals(SpaceOwnerType.ORGANIZATION, result.owners.get(0).type);
        assertEquals(SpaceOwnerRole.ADMIN, result.owners.get(0).role);
    }

    @Test
    void addMember_appendsOwnerWithDefaultRole() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        SpaceEntity root = new SpaceEntity();
        root.owners = List.of(orgOwner("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.pushOwner(anyString(), any())).thenReturn(Uni.createFrom().item(1L));

        service.addMember("root", "m1", null, null).await().indefinitely();

        ArgumentCaptor<SpaceOwner> captor = ArgumentCaptor.forClass(SpaceOwner.class);
        verify(spaceRepository).pushOwner(eq("root"), captor.capture());
        SpaceOwner pushed = captor.getValue();
        assertEquals("m1", pushed.id);
        assertEquals(SpaceOwnerType.USER, pushed.type);
        assertEquals(SpaceOwnerRole.MEMBER, pushed.role);
        assertEquals("member-one", pushed.name);
    }

    @Test
    void addMember_withAdminRoleAndRemark_preservesThem() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        SpaceEntity root = new SpaceEntity();
        root.owners = List.of(orgOwner("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.pushOwner(anyString(), any())).thenReturn(Uni.createFrom().item(1L));

        service.addMember("root", "m1", SpaceOwnerRole.ADMIN, "对接人").await().indefinitely();

        ArgumentCaptor<SpaceOwner> captor = ArgumentCaptor.forClass(SpaceOwner.class);
        verify(spaceRepository).pushOwner(eq("root"), captor.capture());
        assertEquals(SpaceOwnerRole.ADMIN, captor.getValue().role);
        assertEquals("对接人", captor.getValue().remark);
    }

    @Test
    void addMember_existingMember_throws() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        SpaceEntity root = new SpaceEntity();
        root.owners = List.of(userOwner("m1", SpaceOwnerRole.MEMBER));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("root", "m1", null, null).await().indefinitely());
        verify(spaceRepository, never()).pushOwner(anyString(), any());
    }

    @Test
    void addMember_unknownRoot_throws() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().nullItem());

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("root", "m1", null, null).await().indefinitely());
    }

    @Test
    void removeMember_pullsOwner() {
        SpaceEntity root = new SpaceEntity();
        root.owners = List.of(userOwner("m1", SpaceOwnerRole.MEMBER));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.pullOwner("root", "m1")).thenReturn(Uni.createFrom().item(1L));

        service.removeMember("root", "m1").await().indefinitely();

        verify(spaceRepository).pullOwner("root", "m1");
    }

    @Test
    void removeMember_missingMember_throws() {
        SpaceEntity root = new SpaceEntity();
        root.owners = List.of(orgOwner("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.removeMember("root", "m1").await().indefinitely());
        verify(spaceRepository, never()).pullOwner(anyString(), anyString());
    }

    @Test
    void getById_delegates() {
        SpaceEntity expected = new SpaceEntity();
        when(spaceRepository.findById("sid")).thenReturn(Uni.createFrom().item(expected));

        SpaceEntity result = service.getById("sid").await().indefinitely();

        assertSame(expected, result);
    }

    @Test
    void getByParentId_delegates() {
        when(spaceRepository.findByParentId("pid"))
                .thenReturn(Uni.createFrom().item(List.of(new SpaceEntity())));

        List<SpaceEntity> result = service.getByParentId("pid").await().indefinitely();

        assertEquals(1, result.size());
    }

    @Test
    void getRootsByOwner_delegates() {
        when(spaceRepository.findRootsByOwner("org-1", SpaceOwnerType.ORGANIZATION))
                .thenReturn(Uni.createFrom().item(List.of(new SpaceEntity())));

        List<SpaceEntity> result = service.getRootsByOwner("org-1", SpaceOwnerType.ORGANIZATION).await().indefinitely();

        assertEquals(1, result.size());
    }

    @Test
    void update_moveSpace_recalculatesAncestors() {
        ObjectId id = new ObjectId();

        SpaceEntity existing = new SpaceEntity();
        existing.id = id;
        existing.parentId = "old-parent-id";
        existing.level = 1;

        SpaceEntity newParent = new SpaceEntity();
        newParent.level = 0;
        newParent.ancestors = List.of();
        newParent.owners = List.of(orgOwner("org-1"));

        when(spaceRepository.findById(any(ObjectId.class)))
                .thenReturn(Uni.createFrom().item(existing));
        when(spaceRepository.findById("new-parent-id"))
                .thenReturn(Uni.createFrom().item(newParent));
        when(spaceRepository.persistOrUpdateEntity(any())).thenReturn(Uni.createFrom().item(existing));

        SpaceEntity input = new SpaceEntity();
        input.id = id;
        input.parentId = "new-parent-id";
        input.name = "updated";

        SpaceEntity result = service.update(input).await().indefinitely();

        assertEquals("new-parent-id", result.parentId);
        assertEquals(1, result.level);
        assertEquals(List.of("new-parent-id"), result.ancestors);
        assertEquals("updated", result.name);
        assertEquals("org-1", result.owners.get(0).id);
    }
}
