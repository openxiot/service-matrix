package cc.openxiot.site.api.space;

import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.db.space.SpaceRepository;
import io.smallrye.mutiny.Uni;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @InjectMocks
    SpaceService service;

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
        space.organizationId = "org-1";
        when(spaceRepository.persist(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.create(space).await().indefinitely();

        assertEquals(0, result.level);
        assertTrue(result.ancestors.isEmpty());
        assertNull(result.parentId);
        assertEquals("org-1", result.organizationId);
    }

    @Test
    void create_rootSpaceMissingOrgId_throws() {
        SpaceEntity space = new SpaceEntity();
        assertThrows(IllegalArgumentException.class,
                () -> service.create(space).await().indefinitely());
    }

    @Test
    void create_childSpace_calculatesLevelAndAncestors() {
        SpaceEntity parent = new SpaceEntity();
        parent.level = 2;
        parent.ancestors = List.of("root-id", "parent1-id");
        parent.organizationId = "org-1";
        when(spaceRepository.findById("parent-id")).thenReturn(Uni.createFrom().item(parent));

        SpaceEntity space = new SpaceEntity();
        space.parentId = "parent-id";
        when(spaceRepository.persist(any())).thenReturn(Uni.createFrom().item(space));

        SpaceEntity result = service.create(space).await().indefinitely();

        assertEquals(3, result.level);
        assertEquals(List.of("root-id", "parent1-id", "parent-id"), result.ancestors);
        assertEquals("org-1", result.organizationId);
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
    void getRootsByOrganizationId_delegates() {
        when(spaceRepository.findRootByOrganizationId("org-1"))
                .thenReturn(Uni.createFrom().item(List.of(new SpaceEntity())));

        List<SpaceEntity> result = service.getRootsByOrganizationId("org-1").await().indefinitely();

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
        newParent.organizationId = "org-1";

        when(spaceRepository.findById(any(ObjectId.class)))
                .thenReturn(Uni.createFrom().item(existing));
        when(spaceRepository.findById("new-parent-id"))
                .thenReturn(Uni.createFrom().item(newParent));
        when(spaceRepository.update(any())).thenReturn(Uni.createFrom().item(existing));

        SpaceEntity input = new SpaceEntity();
        input.id = id;
        input.parentId = "new-parent-id";
        input.name = "updated";

        SpaceEntity result = service.update(input).await().indefinitely();

        assertEquals("new-parent-id", result.parentId);
        assertEquals(1, result.level);
        assertEquals(List.of("new-parent-id"), result.ancestors);
        assertEquals("updated", result.name);
        assertEquals("org-1", result.organizationId);
    }
}
