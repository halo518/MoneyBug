package com.example.moneybug.service;

import com.example.moneybug.dto.UserDto;
import com.example.moneybug.entity.Store;
import com.example.moneybug.entity.StoreWorker;
import com.example.moneybug.entity.User;
import com.example.moneybug.repository.StoreRepository;
import com.example.moneybug.repository.StoreWorkerRepository;
import com.example.moneybug.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** PATCH /me/profile 검증, 프로필 사진 저장·삭제·읽기 권한. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentProfileTest {

    @Mock UserRepository userRepository;
    @Mock StoreWorkerRepository storeWorkerRepository;
    @Mock StoreRepository storeRepository;
    @Mock FileStorage fileStorage;
    @Mock StoreChangeHub changeHub;
    @InjectMocks DocumentService service;

    final User owner = user(1), me = user(7), peer = user(8), stranger = user(9);
    final Store store = new Store();

    static User user(int id) {
        User u = new User();
        u.setId(id);
        u.setName("u" + id);
        return u;
    }

    StoreWorker row(User u, StoreWorker.Status st) {
        StoreWorker sw = new StoreWorker();
        sw.setStore(store);
        sw.setWorker(u);
        sw.setStatus(st);
        return sw;
    }

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        store.setId(10);
        store.setEmployer(owner);
        me.setFullName("김태우");
        me.setContact("010-1111-2222");
        me.setPhotoUrl("profile-photo/7/old.png");
        when(userRepository.findByIdForUpdate(7)).thenReturn(Optional.of(me));
        for (User u : List.of(owner, me, peer, stranger)) when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        // 7·8 은 매장 10 재직, 9 는 아무 데도 없음
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(7), any())).thenReturn(List.of(row(me, StoreWorker.Status.ACTIVE)));
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(8), any())).thenReturn(List.of(row(peer, StoreWorker.Status.ACTIVE)));
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(9), any())).thenReturn(List.of());
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(1), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    static UserDto.ProfileRequest req(String fullName, String contact) {
        UserDto.ProfileRequest r = new UserDto.ProfileRequest();
        r.setFullName(fullName);
        r.setContact(contact);
        return r;
    }

    void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    @Test
    void blankOrLongNameRejected() {
        assertEquals("NAME_REQUIRED", assertThrows(ApiException.class,
                () -> service.updateProfile(7, req("   ", null))).getCode());
        assertEquals("NAME_TOO_LONG", assertThrows(ApiException.class,
                () -> service.updateProfile(7, req("가".repeat(51), null))).getCode());
        // 이모지는 한 글자로 센다
        assertEquals("😀".repeat(50), service.updateProfile(7, req("😀".repeat(50), null)).getFullName());
    }

    @Test
    void badContactRejected() {
        for (String c : List.of("010-1234-abcd", "+82 10", "0".repeat(21))) {
            ApiException e = assertThrows(ApiException.class, () -> service.updateProfile(7, req(null, c)));
            assertEquals("INVALID_CONTACT", e.getCode());
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
        assertEquals("010-1111-2222", me.getContact());
    }

    @Test
    void emptyContactClearsAndPartialUpdateKeepsOtherField() {
        UserDto.Response r = service.updateProfile(7, req(null, ""));
        assertNull(r.getContact());
        assertEquals("김태우", r.getFullName());

        r = service.updateProfile(7, req("  이태우 ", null));
        assertEquals("이태우", r.getFullName());
        assertNull(r.getContact());

        r = service.updateProfile(7, req(null, "010-9999-0000"));
        assertEquals("010-9999-0000", r.getContact());
        assertEquals("이태우", r.getFullName());
        commit();
        verify(changeHub, atLeastOnce()).bumpAll(List.of(10));
    }

    @Test
    void uploadReplacesOldPhotoAfterCommit() {
        when(fileStorage.saveImage(eq("profile-photo/7"), any())).thenReturn("profile-photo/7/new.png");
        UserDto.Response r = service.uploadPhoto(7, new MockMultipartFile("photo", "a.png", "image/png", new byte[]{1}));
        assertTrue(r.isHasPhoto());
        assertEquals("new.png", r.getPhotoVer());
        commit();
        verify(fileStorage).delete("profile-photo/7/old.png");
        verify(changeHub).bumpAll(List.of(10));
    }

    @Test
    void emptyUploadRejectedAndDeleteRemovesFile() {
        assertEquals("PHOTO_REQUIRED", assertThrows(ApiException.class,
                () -> service.uploadPhoto(7, new MockMultipartFile("photo", new byte[0]))).getCode());
        UserDto.Response r = service.deletePhoto(7);
        assertFalse(r.isHasPhoto());
        assertNull(r.getPhotoVer());
        commit();
        verify(fileStorage).delete("profile-photo/7/old.png");
    }

    @Test
    void photoReadPermission() {
        assertEquals("profile-photo/7/old.png", service.personPhotoPath(7, 7)); // 본인
        assertEquals("profile-photo/7/old.png", service.personPhotoPath(8, 7)); // 같은 매장 동료
        assertEquals("profile-photo/7/old.png", service.personPhotoPath(1, 7)); // 사장님
        owner.setPhotoUrl("profile-photo/1/o.png");
        assertEquals("profile-photo/1/o.png", service.personPhotoPath(7, 1));   // 직원이 사장님 사진
        ApiException e = assertThrows(ApiException.class, () -> service.personPhotoPath(9, 7));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());                       // 남
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.personPhotoPath(7, 8)).getStatus());              // 사진 없음
    }

    @Test
    void leftCoworkerCannotSeePhoto() {
        when(storeWorkerRepository.findByWorkerIdAndStatusIn(eq(8), any())).thenReturn(List.of(row(peer, StoreWorker.Status.LEFT)));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.personPhotoPath(8, 7)).getStatus());
    }
}
