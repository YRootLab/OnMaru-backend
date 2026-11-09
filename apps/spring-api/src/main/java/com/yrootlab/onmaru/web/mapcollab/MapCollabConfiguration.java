package com.yrootlab.onmaru.web.mapcollab;

import com.yrootlab.onmaru.mapcollab.application.CreateMapRoomUseCase;
import com.yrootlab.onmaru.mapcollab.application.GetMapRoomDetailUseCase;
import com.yrootlab.onmaru.mapcollab.application.GetMapRoomSnapshotUseCase;
import com.yrootlab.onmaru.mapcollab.application.IssueMapRoomInviteUseCase;
import com.yrootlab.onmaru.mapcollab.application.JoinMapRoomUseCase;
import com.yrootlab.onmaru.mapcollab.application.ListMyMapRoomsUseCase;
import com.yrootlab.onmaru.mapcollab.application.RevokeMapRoomInviteUseCase;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.port.RoomSessionTracker;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MapCollabConfiguration {

    @Bean
    public RoomRegistry roomRegistry(
            RoomRepository roomRepository,
            PlaceCatalogPort placeCatalogPort,
            MemberProfilePort memberProfilePort,
            RoomEventPublisher roomEventPublisher) {
        return new RoomRegistry(roomRepository, placeCatalogPort, memberProfilePort, roomEventPublisher);
    }

    @Bean
    public CreateMapRoomUseCase createMapRoomUseCase(
            RoomRepository roomRepository,
            MemberProfilePort memberProfilePort,
            PlaceCatalogPort placeCatalogPort,
            RoomEventPublisher roomEventPublisher,
            RoomRegistry roomRegistry) {
        return new CreateMapRoomUseCase(roomRepository, memberProfilePort, placeCatalogPort, roomEventPublisher, roomRegistry);
    }

    @Bean
    public IssueMapRoomInviteUseCase issueMapRoomInviteUseCase(
            RoomRepository roomRepository,
            RoomRegistry roomRegistry) {
        return new IssueMapRoomInviteUseCase(roomRepository, roomRegistry);
    }

    @Bean
    public RevokeMapRoomInviteUseCase revokeMapRoomInviteUseCase(
            RoomRepository roomRepository,
            RoomRegistry roomRegistry) {
        return new RevokeMapRoomInviteUseCase(roomRepository, roomRegistry);
    }

    @Bean
    public JoinMapRoomUseCase joinMapRoomUseCase(
            RoomRepository roomRepository,
            MemberProfilePort memberProfilePort,
            RoomEventPublisher roomEventPublisher,
            RoomRegistry roomRegistry) {
        return new JoinMapRoomUseCase(roomRepository, memberProfilePort, roomEventPublisher, roomRegistry);
    }

    @Bean
    public GetMapRoomDetailUseCase getMapRoomDetailUseCase(
            RoomRepository roomRepository,
            MemberProfilePort memberProfilePort,
            RoomSessionTracker roomSessionTracker) {
        return new GetMapRoomDetailUseCase(roomRepository, memberProfilePort, roomSessionTracker);
    }

    @Bean
    public GetMapRoomSnapshotUseCase getMapRoomSnapshotUseCase(
            RoomRepository roomRepository,
            RoomRegistry roomRegistry) {
        return new GetMapRoomSnapshotUseCase(roomRepository, roomRegistry);
    }

    @Bean
    public ListMyMapRoomsUseCase listMyMapRoomsUseCase(RoomRepository roomRepository) {
        return new ListMyMapRoomsUseCase(roomRepository);
    }
}
