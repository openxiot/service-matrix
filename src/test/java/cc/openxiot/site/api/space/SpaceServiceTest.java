package cc.openxiot.site.api.space;

import cc.openxiot.site.db.space.SpaceEntity;
import cc.openxiot.site.db.space.SpaceRepository;
import cc.openxiot.site.db.space.SpaceUser;
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
import static org.mockito.ArgumentMatchers.*;
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
        when(spaceRepository.findById("unknown")).thenReturn(null);
        assertTrue(service.noAccess("unknown", "user1"));
    }

    @Test
    void noAccess_emptyUsers_returnsTrue() {
        SpaceEntity space = new SpaceEntity();
        space.users = List.of();
        when(spaceRepository.findById("sid")).thenReturn(space);
        assertTrue(service.noAccess("sid", "user1"));
    }

    @Test
    void noAccess_userNotInList_returnsTrue() {
        SpaceEntity space = new SpaceEntity();
        SpaceUser u = new SpaceUser();
        u.userId = "other-user";
        space.users = List.of(u);
        when(spaceRepository.findById("sid")).thenReturn(space);
        assertTrue(service.noAccess("sid", "user1"));
    }

    @Test
    void noAccess_userInList_returnsFalse() {
        SpaceEntity space = new SpaceEntity();
        SpaceUser u = new SpaceUser();
        u.userId = "user1";
        space.users = List.of(u);
        when(spaceRepository.findById("sid")).thenReturn(space);
        assertFalse(service.noAccess("sid", "user1"));
    }

    @Test
    void getDescendants_returnsRootAndChildren() {
        SpaceEntity root = new SpaceEntity();
        SpaceEntity child = new SpaceEntity();
        when(spaceRepository.findById("root-id")).thenReturn(root);
        when(spaceRepository.findByAncestor("root-id")).thenReturn(List.of(child));

        List<SpaceEntity> result = service.getDescendants("root-id");

        assertEquals(2, result.size());
        assertSame(root, result.get(0));
        assertSame(child, result.get(1));
    }

    @Test
    void getDescendants_unknownRoot_returnsEmpty() {
        when(spaceRepository.findById("unknown")).thenReturn(null);
        assertTrue(service.getDescendants("unknown").isEmpty());
    }

    @Test
    void delete_withoutChildren_succeeds() {
        when(spaceRepository.findByParentId("sid")).thenReturn(List.of());
        service.delete("sid");
        verify(spaceRepository).deleteById(anyString());
    }

    @Test
    void delete_withChildren_throws() {
        when(spaceRepository.findByParentId("sid")).thenReturn(List.of(new SpaceEntity()));
        assertThrows(IllegalArgumentException.class, () -> service.delete("sid"));
        verify(spaceRepository, never()).deleteById(anyString());
    }

    @Test
    void create_rootSpace_setsLevelAndAncestors() {
        SpaceEntity space = spy(new SpaceEntity());
        doNothing().when(space).persist();

        SpaceEntity result = service.create(space, "user1");

        assertEquals(0, result.level);
        assertTrue(result.ancestors.isEmpty());
        assertNull(result.parentId);
        assertEquals(1, result.users.size());
        assertEquals("user1", result.users.getFirst().userId);
    }

    @Test
    void create_childSpace_calculatesLevelAndAncestors() {
        SpaceEntity parent = new SpaceEntity();
        parent.level = 2;
        parent.ancestors = List.of("root-id", "parent1-id");
        when(spaceRepository.findById("parent-id")).thenReturn(parent);

        SpaceEntity space = spy(new SpaceEntity());
        space.parentId = "parent-id";
        doNothing().when(space).persist();

        SpaceEntity result = service.create(space, "user1");

        assertEquals(3, result.level);
        assertEquals(List.of("root-id", "parent1-id", "parent-id"), result.ancestors);
    }

    @Test
    void create_childSpaceMissingParent_throws() {
        when(spaceRepository.findById("parent-id")).thenReturn(null);

        SpaceEntity space = new SpaceEntity();
        space.parentId = "parent-id";

        assertThrows(IllegalArgumentException.class, () -> service.create(space, "user1"));
    }

    @Test
    void getById_delegates() {
        SpaceEntity expected = new SpaceEntity();
        when(spaceRepository.findById("sid")).thenReturn(expected);
        assertSame(expected, service.getById("sid"));
    }

    @Test
    void getByParentId_delegates() {
        when(spaceRepository.findByParentId("pid")).thenReturn(List.of(new SpaceEntity()));
        assertEquals(1, service.getByParentId("pid").size());
    }

    @Test
    void getRootsByUserId_delegates() {
        when(spaceRepository.findRootByUserId("user1")).thenReturn(List.of(new SpaceEntity()));
        assertEquals(1, service.getRootsByUserId("user1").size());
    }

    @Test
    void update_moveSpace_recalculatesAncestors() {
        ObjectId id = new ObjectId();

        SpaceEntity existing = spy(new SpaceEntity());
        existing.id = id;
        existing.parentId = "old-parent-id";
        existing.level = 1;
        doNothing().when(existing).update();

        SpaceEntity newParent = new SpaceEntity();
        newParent.level = 0;
        newParent.ancestors = List.of();

        when(spaceRepository.findById(any(ObjectId.class))).thenReturn(existing);
        when(spaceRepository.findById("new-parent-id")).thenReturn(newParent);

        SpaceEntity input = new SpaceEntity();
        input.id = id;
        input.parentId = "new-parent-id";
        input.name = "updated";

        SpaceEntity result = service.update(input);

        assertEquals("new-parent-id", result.parentId);
        assertEquals(1, result.level);
        assertEquals(List.of("new-parent-id"), result.ancestors);
        assertEquals("updated", result.name);
    }
}
