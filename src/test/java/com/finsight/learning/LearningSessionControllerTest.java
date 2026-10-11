package com.finsight.learning;

import com.finsight.global.exception.ApiException;
import com.finsight.global.security.CurrentUser;
import com.finsight.learning.controller.LearningSessionController;
import com.finsight.learning.dto.LearningSessionCreateRequest;
import com.finsight.learning.dto.LearningSessionDetailResponse;
import com.finsight.learning.dto.LearningSessionListItemResponse;
import com.finsight.learning.entity.LearningSession;
import com.finsight.learning.service.LearningSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningSessionControllerTest {
    @Mock CurrentUser currentUser;
    @Mock LearningSessionService service;
    LearningSessionController controller;
    final UUID owner = UUID.randomUUID();
    final UUID newsId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        controller = new LearningSessionController(currentUser, service);
    }

    @Test
    void createReturns201AndExistingReturns200WithSameRecordShape() {
        when(currentUser.id()).thenReturn(owner);
        LearningSession entity = LearningSession.create(owner, newsId);
        UUID sessionId = UUID.randomUUID();
        ReflectionTestUtils.setField(entity, "id", sessionId);
        ReflectionTestUtils.setField(entity, "createdAt", OffsetDateTime.now());
        when(service.createOrGet(owner, newsId, "live"))
                .thenReturn(new LearningSessionService.SessionResult(entity, true),
                        new LearningSessionService.SessionResult(entity, false));

        var created = controller.create(new LearningSessionCreateRequest(newsId, "live"));
        var existing = controller.create(new LearningSessionCreateRequest(newsId, "live"));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals(HttpStatus.OK, existing.getStatusCode());
        assertEquals(created.getBody(), existing.getBody());
        assertEquals(sessionId, created.getBody().id());
    }

    @Test
    void getListUsesDefinedDefaultsFiltersAndDeterministicOrdering() {
        when(currentUser.id()).thenReturn(owner);
        var empty = new PageImpl<LearningSessionListItemResponse>(
                List.of(), PageRequest.of(0, 20), 0);
        when(service.findAll(eq(owner), eq(newsId),
                eq(LearningSession.LearningStatus.completed),
                eq(LearningSession.SessionType.live), any(Pageable.class)))
                .thenReturn(empty);

        var response = controller.findAll(newsId.toString(), "completed", "live", 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().content().isEmpty());
        assertEquals(0, response.getBody().totalPages());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).findAll(eq(owner), eq(newsId),
                eq(LearningSession.LearningStatus.completed),
                eq(LearningSession.SessionType.live), captor.capture());
        Pageable paging = captor.getValue();
        assertEquals(0, paging.getPageNumber());
        assertEquals(20, paging.getPageSize());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
                paging.getSort().getOrderFor("updatedAt").getDirection());
        assertEquals(org.springframework.data.domain.Sort.Direction.ASC,
                paging.getSort().getOrderFor("id").getDirection());
    }

    @Test
    void listRejectsInvalidEnumUuidAndPaginationWithoutCallingService() {
        when(currentUser.id()).thenReturn(owner);
        assertBadRequest(() -> controller.findAll("bad", null, null, 0, 20));
        assertBadRequest(() -> controller.findAll("1-1-1-1-1", null, null, 0, 20));
        assertBadRequest(() -> controller.findAll(null, "INVALID", null, 0, 20));
        assertBadRequest(() -> controller.findAll(null, null, "INVALID", 0, 20));
        assertBadRequest(() -> controller.findAll(null, null, null, -1, 20));
        assertBadRequest(() -> controller.findAll(null, null, null, 0, 0));
        assertBadRequest(() -> controller.findAll(null, null, null, 0, 101));
        assertBadRequest(() -> controller.findAll(null, null, null, Integer.MAX_VALUE, 2));
        verifyNoInteractions(service);
    }

    @Test
    void detailRejectsNoncanonicalIdAndPassesCanonicalIdToService() {
        assertBadRequest(() -> controller.findById("not-a-uuid"));
        assertBadRequest(() -> controller.findById("1-1-1-1-1"));
        UUID sessionId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(owner);
        LearningSessionDetailResponse detail = new LearningSessionDetailResponse(
                sessionId, newsId, "live", "drafting", null, null,
                null, null, null, null);
        when(service.findById(owner, sessionId)).thenReturn(detail);
        assertEquals(detail, controller.findById(sessionId.toString()).getBody());
    }

    private static void assertBadRequest(org.junit.jupiter.api.function.Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }
}
