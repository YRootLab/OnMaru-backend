package com.yrootlab.onmaru.audio.sync;

/**
 * 전체 데이터셋을 키워드 없이 페이지 단위로 읽는 Odii 원천이다.
 *
 * <p>기존 키워드 기반 {@link OdiiPageSource} 구현과 공존할 수 있도록
 * 별도 계약으로 둔다. 동기화 서비스는 이 계약을 우선 사용한다.</p>
 */
public interface OdiiFullCollectionPageSource extends OdiiPageSource {

    OdiiSourcePage fetchFull(String language, int page);

    @Override
    default OdiiSourcePage fetch(String language, String keyword, int page) {
        return fetchFull(language, page);
    }
}
