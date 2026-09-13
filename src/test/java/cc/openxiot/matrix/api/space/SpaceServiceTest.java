package cc.openxiot.matrix.api.space;

import cc.openxiot.matrix.api.space.access.AccountUserLookup;
import cc.openxiot.matrix.db.space.access.SpaceAccess;
import cc.openxiot.matrix.db.space.access.SpaceAccessRole;
import cc.openxiot.matrix.db.space.access.SpaceAccessType;
import cc.openxiot.matrix.db.space.SpaceEntity;
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

    private static SpaceAccess orgAccess(String orgId) {
        return new SpaceAccess(orgId, SpaceAccessType.ORGANIZATION, SpaceAccessRole.ADMIN);
    }

    private static SpaceAccess userAccess(String userId, String role) {
        return new SpaceAccess(userId, SpaceAccessType.USER, role);
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
        space.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.create(space).await().indefinitely();

        assertEquals(0, result.level);
        assertTrue(result.ancestors.isEmpty());
        assertNull(result.parentId);
        assertEquals("org-1", result.accesses.get(0).id);
    }

    @Test
    void create_rootSpaceMissingAccesses_throws() {
        SpaceEntity space = new SpaceEntity();
        assertThrows(IllegalArgumentException.class,
                () -> service.create(space).await().indefinitely());
    }

    @Test
    void create_childSpace_calculatesLevelAndAncestors() {
        SpaceEntity parent = new SpaceEntity();
        parent.level = 2;
        parent.ancestors = List.of("root-id", "parent1-id");
        parent.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.findById("parent-id")).thenReturn(Uni.createFrom().item(parent));

        SpaceEntity space = new SpaceEntity();
        space.parentId = "parent-id";
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.create(space).await().indefinitely();

        assertEquals(3, result.level);
        assertEquals(List.of("root-id", "parent1-id", "parent-id"), result.ancestors);
        assertEquals("org-1", result.accesses.get(0).id);
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
    void createWithAccess_user_resolvesNameAndCreates() {
        when(accountUserLookup.userName("u1")).thenReturn(Uni.createFrom().item("user-one"));
        SpaceEntity space = new SpaceEntity();
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.createWithAccess(space, userAccess("u1", SpaceAccessRole.ADMIN)).await().indefinitely();

        assertEquals(0, result.level);
        assertEquals(1, result.accesses.size());
        SpaceAccess access = result.accesses.get(0);
        assertEquals("u1", access.id);
        assertEquals(SpaceAccessType.USER, access.type);
        assertEquals(SpaceAccessRole.ADMIN, access.role);
        assertEquals("user-one", access.name);
    }

    @Test
    void createWithAccess_org_resolvesNameAndCreates() {
        when(accountUserLookup.orgName("org-1")).thenReturn(Uni.createFrom().item("Acme"));
        SpaceEntity space = new SpaceEntity();
        when(spaceRepository.persistEntity(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.createWithAccess(space, orgAccess("org-1")).await().indefinitely();

        assertEquals("Acme", result.accesses.get(0).name);
        assertEquals(SpaceAccessType.ORGANIZATION, result.accesses.get(0).type);
        assertEquals(SpaceAccessRole.ADMIN, result.accesses.get(0).role);
    }

    @Test
    void addAccess_appendsAccessWithDefaultRole() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.pushAccess(anyString(), any())).thenReturn(Uni.createFrom().item(1L));

        service.addAccess("root", "m1", null, null).await().indefinitely();

        ArgumentCaptor<SpaceAccess> captor = ArgumentCaptor.forClass(SpaceAccess.class);
        verify(spaceRepository).pushAccess(eq("root"), captor.capture());
        SpaceAccess pushed = captor.getValue();
        assertEquals("m1", pushed.id);
        assertEquals(SpaceAccessType.USER, pushed.type);
        assertEquals(SpaceAccessRole.MEMBER, pushed.role);
        assertEquals("member-one", pushed.name);
    }

    @Test
    void addAccess_withAdminRoleAndRemark_preservesThem() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.pushAccess(anyString(), any())).thenReturn(Uni.createFrom().item(1L));

        service.addAccess("root", "m1", SpaceAccessRole.ADMIN, "对接人").await().indefinitely();

        ArgumentCaptor<SpaceAccess> captor = ArgumentCaptor.forClass(SpaceAccess.class);
        verify(spaceRepository).pushAccess(eq("root"), captor.capture());
        assertEquals(SpaceAccessRole.ADMIN, captor.getValue().role);
        assertEquals("对接人", captor.getValue().remark);
    }

    @Test
    void addAccess_existingMember_throws() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(userAccess("m1", SpaceAccessRole.MEMBER));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.addAccess("root", "m1", null, null).await().indefinitely());
        verify(spaceRepository, never()).pushAccess(anyString(), any());
    }

    @Test
    void addAccess_unknownRoot_throws() {
        when(accountUserLookup.userName("m1")).thenReturn(Uni.createFrom().item("member-one"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().nullItem());

        assertThrows(IllegalArgumentException.class,
                () -> service.addAccess("root", "m1", null, null).await().indefinitely());
    }

    @Test
    void removeAccess_pullsAccess() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(userAccess("m1", SpaceAccessRole.MEMBER));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.pullAccess("root", "m1")).thenReturn(Uni.createFrom().item(1L));

        service.removeAccess("root", "m1").await().indefinitely();

        verify(spaceRepository).pullAccess("root", "m1");
    }

    @Test
    void removeAccess_missingMember_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.removeAccess("root", "m1").await().indefinitely());
        verify(spaceRepository, never()).pullAccess(anyString(), anyString());
    }

    @Test
    void updateAccessRole_updatesRole() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(userAccess("m1", SpaceAccessRole.MEMBER));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.updateAccessRole("root", "m1", SpaceAccessRole.ADMIN))
                .thenReturn(Uni.createFrom().item(1L));

        service.updateAccessRole("root", "m1", SpaceAccessRole.ADMIN).await().indefinitely();

        verify(spaceRepository).updateAccessRole("root", "m1", SpaceAccessRole.ADMIN);
    }

    @Test
    void updateAccessRole_unknownMember_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateAccessRole("root", "m1", SpaceAccessRole.ADMIN).await().indefinitely());
        verify(spaceRepository, never()).updateAccessRole(anyString(), anyString(), anyString());
    }

    @Test
    void updateAccessRole_demoteLastAdmin_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(userAccess("m1", SpaceAccessRole.ADMIN));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateAccessRole("root", "m1", SpaceAccessRole.MEMBER).await().indefinitely());
        verify(spaceRepository, never()).updateAccessRole(anyString(), anyString(), anyString());
    }

    @Test
    void updateAccessRole_withOtherAdmin_allows() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(
                userAccess("m1", SpaceAccessRole.ADMIN),
                userAccess("m2", SpaceAccessRole.ADMIN));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.updateAccessRole("root", "m1", SpaceAccessRole.MEMBER))
                .thenReturn(Uni.createFrom().item(1L));

        service.updateAccessRole("root", "m1", SpaceAccessRole.MEMBER).await().indefinitely();

        verify(spaceRepository).updateAccessRole("root", "m1", SpaceAccessRole.MEMBER);
    }

    @Test
    void updateAccessRole_withOrgEntry_lastAdmin_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(
                orgAccess("org-1"),
                userAccess("m1", SpaceAccessRole.ADMIN));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateAccessRole("root", "m1", SpaceAccessRole.MEMBER).await().indefinitely());
        verify(spaceRepository, never()).updateAccessRole(anyString(), anyString(), anyString());
    }

    @Test
    void updateAccessRemark_updatesRemark() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(userAccess("m1", SpaceAccessRole.MEMBER));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));
        when(spaceRepository.updateAccessRemark("root", "m1", "对接人"))
                .thenReturn(Uni.createFrom().item(1L));

        service.updateAccessRemark("root", "m1", "对接人").await().indefinitely();

        verify(spaceRepository).updateAccessRemark("root", "m1", "对接人");
    }

    @Test
    void updateAccessRemark_missingMember_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(orgAccess("org-1"));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateAccessRemark("root", "m1", "备注").await().indefinitely());
        verify(spaceRepository, never()).updateAccessRemark(anyString(), anyString(), anyString());
    }

    @Test
    void removeAccess_lastAdmin_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(userAccess("m1", SpaceAccessRole.ADMIN));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.removeAccess("root", "m1").await().indefinitely());
        verify(spaceRepository, never()).pullAccess(anyString(), anyString());
    }

    @Test
    void removeAccess_withOrgEntry_lastAdmin_throws() {
        SpaceEntity root = new SpaceEntity();
        root.accesses = List.of(
                orgAccess("org-1"),
                userAccess("m1", SpaceAccessRole.ADMIN));
        when(spaceRepository.findById("root")).thenReturn(Uni.createFrom().item(root));

        assertThrows(IllegalArgumentException.class,
                () -> service.removeAccess("root", "m1").await().indefinitely());
        verify(spaceRepository, never()).pullAccess(anyString(), anyString());
    }

    @Test
    void getById_delegates() {
        SpaceEntity expected = new SpaceEntity();
        when(spaceRepository.findById("sid")).thenReturn(Uni.createFrom().item(expected));

        SpaceEntity result = service.getById("sid").await().indefinitely();

        assertSame(expected, result);
    }

    @Test
    void getRootsByAccess_delegates() {
        when(spaceRepository.findRootsByAccess("org-1", SpaceAccessType.ORGANIZATION))
                .thenReturn(Uni.createFrom().item(List.of(new SpaceEntity())));

        List<SpaceEntity> result = service.getRootsByAccess("org-1", SpaceAccessType.ORGANIZATION).await().indefinitely();

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
        newParent.accesses = List.of(orgAccess("org-1"));

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
        assertEquals("org-1", result.accesses.get(0).id);
    }
}
