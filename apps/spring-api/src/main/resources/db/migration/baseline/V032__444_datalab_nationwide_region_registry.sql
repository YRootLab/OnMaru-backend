-- onmaru-checksum: datalab-nationwide-region-registry-v032-20260927
-- Issue: #444 Restore the nationwide DataLab administrative heatmap registry.
-- Source snapshot: KTO DataLab regional visitor API response for 2026-08-23.

CREATE TEMP TABLE onmaru_datalab_region_seed (
    provider_code varchar NOT NULL,
    provider_name varchar NOT NULL,
    level onmaru.catalog_region_level NOT NULL,
    parent_provider_code varchar,
    internal_code varchar NOT NULL
) ON COMMIT DROP;

INSERT INTO onmaru_datalab_region_seed (
    provider_code, provider_name, level, parent_provider_code, internal_code
) VALUES
    ('11', '서울특별시', 'SIDO', NULL, 'kr-11'),
    ('12', '전남광주통합특별시', 'SIDO', NULL, 'kr-datalab-12'),
    ('26', '부산광역시', 'SIDO', NULL, 'kr-datalab-26'),
    ('27', '대구광역시', 'SIDO', NULL, 'kr-datalab-27'),
    ('28', '인천광역시', 'SIDO', NULL, 'kr-datalab-28'),
    ('30', '대전광역시', 'SIDO', NULL, 'kr-datalab-30'),
    ('31', '울산광역시', 'SIDO', NULL, 'kr-datalab-31'),
    ('36', '세종특별자치시', 'SIDO', NULL, 'kr-datalab-36'),
    ('41', '경기도', 'SIDO', NULL, 'kr-datalab-41'),
    ('43', '충청북도', 'SIDO', NULL, 'kr-datalab-43'),
    ('44', '충청남도', 'SIDO', NULL, 'kr-datalab-44'),
    ('47', '경상북도', 'SIDO', NULL, 'kr-datalab-47'),
    ('48', '경상남도', 'SIDO', NULL, 'kr-datalab-48'),
    ('50', '제주특별자치도', 'SIDO', NULL, 'kr-datalab-50'),
    ('51', '강원특별자치도', 'SIDO', NULL, 'kr-datalab-51'),
    ('52', '전북특별자치도', 'SIDO', NULL, 'kr-45'),
    ('11110', '종로구', 'SIGUNGU', '11', 'kr-11-jongno'),
    ('11140', '중구', 'SIGUNGU', '11', 'kr-datalab-11140'),
    ('11170', '용산구', 'SIGUNGU', '11', 'kr-datalab-11170'),
    ('11200', '성동구', 'SIGUNGU', '11', 'kr-datalab-11200'),
    ('11215', '광진구', 'SIGUNGU', '11', 'kr-datalab-11215'),
    ('11230', '동대문구', 'SIGUNGU', '11', 'kr-datalab-11230'),
    ('11260', '중랑구', 'SIGUNGU', '11', 'kr-datalab-11260'),
    ('11290', '성북구', 'SIGUNGU', '11', 'kr-datalab-11290'),
    ('11305', '강북구', 'SIGUNGU', '11', 'kr-datalab-11305'),
    ('11320', '도봉구', 'SIGUNGU', '11', 'kr-datalab-11320'),
    ('11350', '노원구', 'SIGUNGU', '11', 'kr-datalab-11350'),
    ('11380', '은평구', 'SIGUNGU', '11', 'kr-datalab-11380'),
    ('11410', '서대문구', 'SIGUNGU', '11', 'kr-datalab-11410'),
    ('11440', '마포구', 'SIGUNGU', '11', 'kr-datalab-11440'),
    ('11470', '양천구', 'SIGUNGU', '11', 'kr-datalab-11470'),
    ('11500', '강서구', 'SIGUNGU', '11', 'kr-datalab-11500'),
    ('11530', '구로구', 'SIGUNGU', '11', 'kr-datalab-11530'),
    ('11545', '금천구', 'SIGUNGU', '11', 'kr-datalab-11545'),
    ('11560', '영등포구', 'SIGUNGU', '11', 'kr-datalab-11560'),
    ('11590', '동작구', 'SIGUNGU', '11', 'kr-datalab-11590'),
    ('11620', '관악구', 'SIGUNGU', '11', 'kr-datalab-11620'),
    ('11650', '서초구', 'SIGUNGU', '11', 'kr-datalab-11650'),
    ('11680', '강남구', 'SIGUNGU', '11', 'kr-datalab-11680'),
    ('11710', '송파구', 'SIGUNGU', '11', 'kr-datalab-11710'),
    ('11740', '강동구', 'SIGUNGU', '11', 'kr-datalab-11740'),
    ('12110', '목포시', 'SIGUNGU', '12', 'kr-datalab-12110'),
    ('12130', '여수시', 'SIGUNGU', '12', 'kr-datalab-12130'),
    ('12150', '순천시', 'SIGUNGU', '12', 'kr-datalab-12150'),
    ('12170', '나주시', 'SIGUNGU', '12', 'kr-datalab-12170'),
    ('12190', '광양시', 'SIGUNGU', '12', 'kr-datalab-12190'),
    ('12210', '동구', 'SIGUNGU', '12', 'kr-datalab-12210'),
    ('12240', '서구', 'SIGUNGU', '12', 'kr-datalab-12240'),
    ('12270', '남구', 'SIGUNGU', '12', 'kr-datalab-12270'),
    ('12300', '북구', 'SIGUNGU', '12', 'kr-datalab-12300'),
    ('12330', '광산구', 'SIGUNGU', '12', 'kr-datalab-12330'),
    ('12710', '담양군', 'SIGUNGU', '12', 'kr-datalab-12710'),
    ('12720', '곡성군', 'SIGUNGU', '12', 'kr-datalab-12720'),
    ('12730', '구례군', 'SIGUNGU', '12', 'kr-datalab-12730'),
    ('12740', '고흥군', 'SIGUNGU', '12', 'kr-datalab-12740'),
    ('12750', '보성군', 'SIGUNGU', '12', 'kr-datalab-12750'),
    ('12760', '화순군', 'SIGUNGU', '12', 'kr-datalab-12760'),
    ('12770', '장흥군', 'SIGUNGU', '12', 'kr-datalab-12770'),
    ('12780', '강진군', 'SIGUNGU', '12', 'kr-datalab-12780'),
    ('12790', '해남군', 'SIGUNGU', '12', 'kr-datalab-12790'),
    ('12800', '영암군', 'SIGUNGU', '12', 'kr-datalab-12800'),
    ('12810', '무안군', 'SIGUNGU', '12', 'kr-datalab-12810'),
    ('12820', '함평군', 'SIGUNGU', '12', 'kr-datalab-12820'),
    ('12830', '영광군', 'SIGUNGU', '12', 'kr-datalab-12830'),
    ('12840', '장성군', 'SIGUNGU', '12', 'kr-datalab-12840'),
    ('12850', '완도군', 'SIGUNGU', '12', 'kr-datalab-12850'),
    ('12860', '진도군', 'SIGUNGU', '12', 'kr-datalab-12860'),
    ('12870', '신안군', 'SIGUNGU', '12', 'kr-datalab-12870'),
    ('26110', '중구', 'SIGUNGU', '26', 'kr-datalab-26110'),
    ('26140', '서구', 'SIGUNGU', '26', 'kr-datalab-26140'),
    ('26170', '동구', 'SIGUNGU', '26', 'kr-datalab-26170'),
    ('26200', '영도구', 'SIGUNGU', '26', 'kr-datalab-26200'),
    ('26230', '부산진구', 'SIGUNGU', '26', 'kr-datalab-26230'),
    ('26260', '동래구', 'SIGUNGU', '26', 'kr-datalab-26260'),
    ('26290', '남구', 'SIGUNGU', '26', 'kr-datalab-26290'),
    ('26320', '북구', 'SIGUNGU', '26', 'kr-datalab-26320'),
    ('26350', '해운대구', 'SIGUNGU', '26', 'kr-datalab-26350'),
    ('26380', '사하구', 'SIGUNGU', '26', 'kr-datalab-26380'),
    ('26410', '금정구', 'SIGUNGU', '26', 'kr-datalab-26410'),
    ('26440', '강서구', 'SIGUNGU', '26', 'kr-datalab-26440'),
    ('26470', '연제구', 'SIGUNGU', '26', 'kr-datalab-26470'),
    ('26500', '수영구', 'SIGUNGU', '26', 'kr-datalab-26500'),
    ('26530', '사상구', 'SIGUNGU', '26', 'kr-datalab-26530'),
    ('26710', '기장군', 'SIGUNGU', '26', 'kr-datalab-26710'),
    ('27110', '중구', 'SIGUNGU', '27', 'kr-datalab-27110'),
    ('27140', '동구', 'SIGUNGU', '27', 'kr-datalab-27140'),
    ('27170', '서구', 'SIGUNGU', '27', 'kr-datalab-27170'),
    ('27200', '남구', 'SIGUNGU', '27', 'kr-datalab-27200'),
    ('27230', '북구', 'SIGUNGU', '27', 'kr-datalab-27230'),
    ('27260', '수성구', 'SIGUNGU', '27', 'kr-datalab-27260'),
    ('27290', '달서구', 'SIGUNGU', '27', 'kr-datalab-27290'),
    ('27710', '달성군', 'SIGUNGU', '27', 'kr-datalab-27710'),
    ('27720', '군위군', 'SIGUNGU', '27', 'kr-datalab-27720'),
    ('28125', '제물포구', 'SIGUNGU', '28', 'kr-datalab-28125'),
    ('28155', '영종구', 'SIGUNGU', '28', 'kr-datalab-28155'),
    ('28177', '미추홀구', 'SIGUNGU', '28', 'kr-datalab-28177'),
    ('28185', '연수구', 'SIGUNGU', '28', 'kr-datalab-28185'),
    ('28200', '남동구', 'SIGUNGU', '28', 'kr-datalab-28200'),
    ('28237', '부평구', 'SIGUNGU', '28', 'kr-datalab-28237'),
    ('28245', '계양구', 'SIGUNGU', '28', 'kr-datalab-28245'),
    ('28275', '서해구', 'SIGUNGU', '28', 'kr-datalab-28275'),
    ('28290', '검단구', 'SIGUNGU', '28', 'kr-datalab-28290'),
    ('28710', '강화군', 'SIGUNGU', '28', 'kr-datalab-28710'),
    ('28720', '옹진군', 'SIGUNGU', '28', 'kr-datalab-28720'),
    ('30110', '동구', 'SIGUNGU', '30', 'kr-datalab-30110'),
    ('30140', '중구', 'SIGUNGU', '30', 'kr-datalab-30140'),
    ('30170', '서구', 'SIGUNGU', '30', 'kr-datalab-30170'),
    ('30200', '유성구', 'SIGUNGU', '30', 'kr-datalab-30200'),
    ('30230', '대덕구', 'SIGUNGU', '30', 'kr-datalab-30230'),
    ('31110', '중구', 'SIGUNGU', '31', 'kr-datalab-31110'),
    ('31140', '남구', 'SIGUNGU', '31', 'kr-datalab-31140'),
    ('31170', '동구', 'SIGUNGU', '31', 'kr-datalab-31170'),
    ('31200', '북구', 'SIGUNGU', '31', 'kr-datalab-31200'),
    ('31710', '울주군', 'SIGUNGU', '31', 'kr-datalab-31710'),
    ('36110', '세종특별자치시', 'SIGUNGU', '36', 'kr-datalab-36110'),
    ('41110', '수원시', 'SIGUNGU', '41', 'kr-datalab-41110'),
    ('41111', '수원시 장안구', 'SIGUNGU', '41', 'kr-datalab-41111'),
    ('41113', '수원시 권선구', 'SIGUNGU', '41', 'kr-datalab-41113'),
    ('41115', '수원시 팔달구', 'SIGUNGU', '41', 'kr-datalab-41115'),
    ('41117', '수원시 영통구', 'SIGUNGU', '41', 'kr-datalab-41117'),
    ('41130', '성남시', 'SIGUNGU', '41', 'kr-datalab-41130'),
    ('41131', '성남시 수정구', 'SIGUNGU', '41', 'kr-datalab-41131'),
    ('41133', '성남시 중원구', 'SIGUNGU', '41', 'kr-datalab-41133'),
    ('41135', '성남시 분당구', 'SIGUNGU', '41', 'kr-datalab-41135'),
    ('41150', '의정부시', 'SIGUNGU', '41', 'kr-datalab-41150'),
    ('41170', '안양시', 'SIGUNGU', '41', 'kr-datalab-41170'),
    ('41171', '안양시 만안구', 'SIGUNGU', '41', 'kr-datalab-41171'),
    ('41173', '안양시 동안구', 'SIGUNGU', '41', 'kr-datalab-41173'),
    ('41190', '부천시', 'SIGUNGU', '41', 'kr-datalab-41190'),
    ('41192', '부천시 원미구', 'SIGUNGU', '41', 'kr-datalab-41192'),
    ('41194', '부천시 소사구', 'SIGUNGU', '41', 'kr-datalab-41194'),
    ('41196', '부천시 오정구', 'SIGUNGU', '41', 'kr-datalab-41196'),
    ('41210', '광명시', 'SIGUNGU', '41', 'kr-datalab-41210'),
    ('41220', '평택시', 'SIGUNGU', '41', 'kr-datalab-41220'),
    ('41250', '동두천시', 'SIGUNGU', '41', 'kr-datalab-41250'),
    ('41270', '안산시', 'SIGUNGU', '41', 'kr-datalab-41270'),
    ('41271', '안산시 상록구', 'SIGUNGU', '41', 'kr-datalab-41271'),
    ('41273', '안산시 단원구', 'SIGUNGU', '41', 'kr-datalab-41273'),
    ('41280', '고양시', 'SIGUNGU', '41', 'kr-datalab-41280'),
    ('41281', '고양시 덕양구', 'SIGUNGU', '41', 'kr-datalab-41281'),
    ('41285', '고양시 일산동구', 'SIGUNGU', '41', 'kr-datalab-41285'),
    ('41287', '고양시 일산서구', 'SIGUNGU', '41', 'kr-datalab-41287'),
    ('41290', '과천시', 'SIGUNGU', '41', 'kr-datalab-41290'),
    ('41310', '구리시', 'SIGUNGU', '41', 'kr-datalab-41310'),
    ('41360', '남양주시', 'SIGUNGU', '41', 'kr-datalab-41360'),
    ('41370', '오산시', 'SIGUNGU', '41', 'kr-datalab-41370'),
    ('41390', '시흥시', 'SIGUNGU', '41', 'kr-datalab-41390'),
    ('41410', '군포시', 'SIGUNGU', '41', 'kr-datalab-41410'),
    ('41430', '의왕시', 'SIGUNGU', '41', 'kr-datalab-41430'),
    ('41450', '하남시', 'SIGUNGU', '41', 'kr-datalab-41450'),
    ('41460', '용인시', 'SIGUNGU', '41', 'kr-datalab-41460'),
    ('41461', '용인시 처인구', 'SIGUNGU', '41', 'kr-datalab-41461'),
    ('41463', '용인시 기흥구', 'SIGUNGU', '41', 'kr-datalab-41463'),
    ('41465', '용인시 수지구', 'SIGUNGU', '41', 'kr-datalab-41465'),
    ('41480', '파주시', 'SIGUNGU', '41', 'kr-datalab-41480'),
    ('41500', '이천시', 'SIGUNGU', '41', 'kr-datalab-41500'),
    ('41550', '안성시', 'SIGUNGU', '41', 'kr-datalab-41550'),
    ('41570', '김포시', 'SIGUNGU', '41', 'kr-datalab-41570'),
    ('41590', '화성시', 'SIGUNGU', '41', 'kr-datalab-41590'),
    ('41591', '화성시 만세구', 'SIGUNGU', '41', 'kr-datalab-41591'),
    ('41593', '화성시 효행구', 'SIGUNGU', '41', 'kr-datalab-41593'),
    ('41595', '화성시 병점구', 'SIGUNGU', '41', 'kr-datalab-41595'),
    ('41597', '화성시 동탄구', 'SIGUNGU', '41', 'kr-datalab-41597'),
    ('41610', '광주시', 'SIGUNGU', '41', 'kr-datalab-41610'),
    ('41630', '양주시', 'SIGUNGU', '41', 'kr-datalab-41630'),
    ('41650', '포천시', 'SIGUNGU', '41', 'kr-datalab-41650'),
    ('41670', '여주시', 'SIGUNGU', '41', 'kr-datalab-41670'),
    ('41800', '연천군', 'SIGUNGU', '41', 'kr-datalab-41800'),
    ('41820', '가평군', 'SIGUNGU', '41', 'kr-datalab-41820'),
    ('41830', '양평군', 'SIGUNGU', '41', 'kr-datalab-41830'),
    ('43110', '청주시', 'SIGUNGU', '43', 'kr-datalab-43110'),
    ('43111', '청주시 상당구', 'SIGUNGU', '43', 'kr-datalab-43111'),
    ('43112', '청주시 서원구', 'SIGUNGU', '43', 'kr-datalab-43112'),
    ('43113', '청주시 흥덕구', 'SIGUNGU', '43', 'kr-datalab-43113'),
    ('43114', '청주시 청원구', 'SIGUNGU', '43', 'kr-datalab-43114'),
    ('43130', '충주시', 'SIGUNGU', '43', 'kr-datalab-43130'),
    ('43150', '제천시', 'SIGUNGU', '43', 'kr-datalab-43150'),
    ('43720', '보은군', 'SIGUNGU', '43', 'kr-datalab-43720'),
    ('43730', '옥천군', 'SIGUNGU', '43', 'kr-datalab-43730'),
    ('43740', '영동군', 'SIGUNGU', '43', 'kr-datalab-43740'),
    ('43745', '증평군', 'SIGUNGU', '43', 'kr-datalab-43745'),
    ('43750', '진천군', 'SIGUNGU', '43', 'kr-datalab-43750'),
    ('43760', '괴산군', 'SIGUNGU', '43', 'kr-datalab-43760'),
    ('43770', '음성군', 'SIGUNGU', '43', 'kr-datalab-43770'),
    ('43800', '단양군', 'SIGUNGU', '43', 'kr-datalab-43800'),
    ('44130', '천안시', 'SIGUNGU', '44', 'kr-datalab-44130'),
    ('44131', '천안시 동남구', 'SIGUNGU', '44', 'kr-datalab-44131'),
    ('44133', '천안시 서북구', 'SIGUNGU', '44', 'kr-datalab-44133'),
    ('44150', '공주시', 'SIGUNGU', '44', 'kr-datalab-44150'),
    ('44180', '보령시', 'SIGUNGU', '44', 'kr-datalab-44180'),
    ('44200', '아산시', 'SIGUNGU', '44', 'kr-datalab-44200'),
    ('44210', '서산시', 'SIGUNGU', '44', 'kr-datalab-44210'),
    ('44230', '논산시', 'SIGUNGU', '44', 'kr-datalab-44230'),
    ('44250', '계룡시', 'SIGUNGU', '44', 'kr-datalab-44250'),
    ('44270', '당진시', 'SIGUNGU', '44', 'kr-datalab-44270'),
    ('44710', '금산군', 'SIGUNGU', '44', 'kr-datalab-44710'),
    ('44760', '부여군', 'SIGUNGU', '44', 'kr-datalab-44760'),
    ('44770', '서천군', 'SIGUNGU', '44', 'kr-datalab-44770'),
    ('44790', '청양군', 'SIGUNGU', '44', 'kr-datalab-44790'),
    ('44800', '홍성군', 'SIGUNGU', '44', 'kr-datalab-44800'),
    ('44810', '예산군', 'SIGUNGU', '44', 'kr-datalab-44810'),
    ('44825', '태안군', 'SIGUNGU', '44', 'kr-datalab-44825'),
    ('47110', '포항시', 'SIGUNGU', '47', 'kr-datalab-47110'),
    ('47111', '포항시 남구', 'SIGUNGU', '47', 'kr-datalab-47111'),
    ('47113', '포항시 북구', 'SIGUNGU', '47', 'kr-datalab-47113'),
    ('47130', '경주시', 'SIGUNGU', '47', 'kr-datalab-47130'),
    ('47150', '김천시', 'SIGUNGU', '47', 'kr-datalab-47150'),
    ('47170', '안동시', 'SIGUNGU', '47', 'kr-datalab-47170'),
    ('47190', '구미시', 'SIGUNGU', '47', 'kr-datalab-47190'),
    ('47210', '영주시', 'SIGUNGU', '47', 'kr-datalab-47210'),
    ('47230', '영천시', 'SIGUNGU', '47', 'kr-datalab-47230'),
    ('47250', '상주시', 'SIGUNGU', '47', 'kr-datalab-47250'),
    ('47280', '문경시', 'SIGUNGU', '47', 'kr-datalab-47280'),
    ('47290', '경산시', 'SIGUNGU', '47', 'kr-datalab-47290'),
    ('47730', '의성군', 'SIGUNGU', '47', 'kr-datalab-47730'),
    ('47750', '청송군', 'SIGUNGU', '47', 'kr-datalab-47750'),
    ('47760', '영양군', 'SIGUNGU', '47', 'kr-datalab-47760'),
    ('47770', '영덕군', 'SIGUNGU', '47', 'kr-datalab-47770'),
    ('47820', '청도군', 'SIGUNGU', '47', 'kr-datalab-47820'),
    ('47830', '고령군', 'SIGUNGU', '47', 'kr-datalab-47830'),
    ('47840', '성주군', 'SIGUNGU', '47', 'kr-datalab-47840'),
    ('47850', '칠곡군', 'SIGUNGU', '47', 'kr-datalab-47850'),
    ('47900', '예천군', 'SIGUNGU', '47', 'kr-datalab-47900'),
    ('47920', '봉화군', 'SIGUNGU', '47', 'kr-datalab-47920'),
    ('47930', '울진군', 'SIGUNGU', '47', 'kr-datalab-47930'),
    ('47940', '울릉군', 'SIGUNGU', '47', 'kr-datalab-47940'),
    ('48120', '창원시', 'SIGUNGU', '48', 'kr-datalab-48120'),
    ('48121', '창원시 의창구', 'SIGUNGU', '48', 'kr-datalab-48121'),
    ('48123', '창원시 성산구', 'SIGUNGU', '48', 'kr-datalab-48123'),
    ('48125', '창원시 마산합포구', 'SIGUNGU', '48', 'kr-datalab-48125'),
    ('48127', '창원시 마산회원구', 'SIGUNGU', '48', 'kr-datalab-48127'),
    ('48129', '창원시 진해구', 'SIGUNGU', '48', 'kr-datalab-48129'),
    ('48170', '진주시', 'SIGUNGU', '48', 'kr-datalab-48170'),
    ('48220', '통영시', 'SIGUNGU', '48', 'kr-datalab-48220'),
    ('48240', '사천시', 'SIGUNGU', '48', 'kr-datalab-48240'),
    ('48250', '김해시', 'SIGUNGU', '48', 'kr-datalab-48250'),
    ('48270', '밀양시', 'SIGUNGU', '48', 'kr-datalab-48270'),
    ('48310', '거제시', 'SIGUNGU', '48', 'kr-datalab-48310'),
    ('48330', '양산시', 'SIGUNGU', '48', 'kr-datalab-48330'),
    ('48720', '의령군', 'SIGUNGU', '48', 'kr-datalab-48720'),
    ('48730', '함안군', 'SIGUNGU', '48', 'kr-datalab-48730'),
    ('48740', '창녕군', 'SIGUNGU', '48', 'kr-datalab-48740'),
    ('48820', '고성군', 'SIGUNGU', '48', 'kr-datalab-48820'),
    ('48840', '남해군', 'SIGUNGU', '48', 'kr-datalab-48840'),
    ('48850', '하동군', 'SIGUNGU', '48', 'kr-datalab-48850'),
    ('48860', '산청군', 'SIGUNGU', '48', 'kr-datalab-48860'),
    ('48870', '함양군', 'SIGUNGU', '48', 'kr-datalab-48870'),
    ('48880', '거창군', 'SIGUNGU', '48', 'kr-datalab-48880'),
    ('48890', '합천군', 'SIGUNGU', '48', 'kr-datalab-48890'),
    ('50110', '제주시', 'SIGUNGU', '50', 'kr-datalab-50110'),
    ('50130', '서귀포시', 'SIGUNGU', '50', 'kr-datalab-50130'),
    ('51110', '춘천시', 'SIGUNGU', '51', 'kr-datalab-51110'),
    ('51130', '원주시', 'SIGUNGU', '51', 'kr-datalab-51130'),
    ('51150', '강릉시', 'SIGUNGU', '51', 'kr-datalab-51150'),
    ('51170', '동해시', 'SIGUNGU', '51', 'kr-datalab-51170'),
    ('51190', '태백시', 'SIGUNGU', '51', 'kr-datalab-51190'),
    ('51210', '속초시', 'SIGUNGU', '51', 'kr-datalab-51210'),
    ('51230', '삼척시', 'SIGUNGU', '51', 'kr-datalab-51230'),
    ('51720', '홍천군', 'SIGUNGU', '51', 'kr-datalab-51720'),
    ('51730', '횡성군', 'SIGUNGU', '51', 'kr-datalab-51730'),
    ('51750', '영월군', 'SIGUNGU', '51', 'kr-datalab-51750'),
    ('51760', '평창군', 'SIGUNGU', '51', 'kr-datalab-51760'),
    ('51770', '정선군', 'SIGUNGU', '51', 'kr-datalab-51770'),
    ('51780', '철원군', 'SIGUNGU', '51', 'kr-datalab-51780'),
    ('51790', '화천군', 'SIGUNGU', '51', 'kr-datalab-51790'),
    ('51800', '양구군', 'SIGUNGU', '51', 'kr-datalab-51800'),
    ('51810', '인제군', 'SIGUNGU', '51', 'kr-datalab-51810'),
    ('51820', '고성군', 'SIGUNGU', '51', 'kr-datalab-51820'),
    ('51830', '양양군', 'SIGUNGU', '51', 'kr-datalab-51830'),
    ('52110', '전주시', 'SIGUNGU', '52', 'kr-45-jeonju'),
    ('52111', '전주시 완산구', 'SIGUNGU', '52', 'kr-datalab-52111'),
    ('52113', '전주시 덕진구', 'SIGUNGU', '52', 'kr-datalab-52113'),
    ('52130', '군산시', 'SIGUNGU', '52', 'kr-datalab-52130'),
    ('52140', '익산시', 'SIGUNGU', '52', 'kr-datalab-52140'),
    ('52180', '정읍시', 'SIGUNGU', '52', 'kr-datalab-52180'),
    ('52190', '남원시', 'SIGUNGU', '52', 'kr-datalab-52190'),
    ('52210', '김제시', 'SIGUNGU', '52', 'kr-datalab-52210'),
    ('52710', '완주군', 'SIGUNGU', '52', 'kr-datalab-52710'),
    ('52720', '진안군', 'SIGUNGU', '52', 'kr-datalab-52720'),
    ('52730', '무주군', 'SIGUNGU', '52', 'kr-datalab-52730'),
    ('52740', '장수군', 'SIGUNGU', '52', 'kr-datalab-52740'),
    ('52750', '임실군', 'SIGUNGU', '52', 'kr-datalab-52750'),
    ('52770', '순창군', 'SIGUNGU', '52', 'kr-datalab-52770'),
    ('52790', '고창군', 'SIGUNGU', '52', 'kr-datalab-52790'),
    ('52800', '부안군', 'SIGUNGU', '52', 'kr-datalab-52800');

INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
SELECT
    md5('onmaru-region|' || seed.internal_code)::uuid,
    NULL,
    seed.internal_code,
    seed.provider_name,
    seed.level,
    true
FROM onmaru_datalab_region_seed seed
WHERE seed.level = 'SIDO'
  AND EXISTS (
      SELECT 1 FROM onmaru.catalog_active_datasets active
      WHERE active.dataset = 'kto-korean-tour'
  )
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name, active = true;

INSERT INTO onmaru.catalog_regions (id, parent_id, code, name, level, active)
SELECT
    md5('onmaru-region|' || seed.internal_code)::uuid,
    parent.id,
    seed.internal_code,
    seed.provider_name,
    seed.level,
    true
FROM onmaru_datalab_region_seed seed
JOIN onmaru_datalab_region_seed parent_seed
  ON parent_seed.provider_code = seed.parent_provider_code
 AND parent_seed.level = 'SIDO'
JOIN onmaru.catalog_regions parent ON parent.code = parent_seed.internal_code
WHERE seed.level = 'SIGUNGU'
  AND EXISTS (
      SELECT 1 FROM onmaru.catalog_active_datasets active
      WHERE active.dataset = 'kto-korean-tour'
  )
ON CONFLICT (code) DO UPDATE
SET parent_id = EXCLUDED.parent_id, name = EXCLUDED.name, active = true;

INSERT INTO onmaru.catalog_region_source_codes (
    provider, dataset, source_code, valid_from, valid_to, region_id
)
SELECT
    'KTO_DATALAB',
    'visitor',
    seed.level::text || ':' || seed.provider_code,
    DATE '2026-09-26',
    NULL,
    region.id
FROM onmaru_datalab_region_seed seed
JOIN onmaru.catalog_regions region ON region.code = seed.internal_code
ON CONFLICT (provider, dataset, source_code, valid_from) DO NOTHING;

INSERT INTO onmaru.catalog_region_source_code_verifications (
    provider, dataset, source_code, valid_from,
    official_source_url, verified_at, verified_by
)
SELECT
    source.provider,
    source.dataset,
    source.source_code,
    source.valid_from,
    'https://www.data.go.kr/data/15101972/openapi.do',
    TIMESTAMPTZ '2026-09-27 18:30:00+09',
    'onmaru-catalog-data-verification'
FROM onmaru.catalog_region_source_codes source
JOIN onmaru_datalab_region_seed seed
  ON source.source_code = seed.level::text || ':' || seed.provider_code
WHERE source.provider = 'KTO_DATALAB'
  AND source.dataset = 'visitor'
  AND source.valid_from = DATE '2026-09-26'
ON CONFLICT (provider, dataset, source_code, valid_from) DO NOTHING;

INSERT INTO onmaru.catalog_datalab_region_mappings (
    provider, dataset, source_code, valid_from, valid_to,
    region_id, level, name, source_url, source_observed_at,
    verified_by, verified_at, status
)
SELECT
    source.provider,
    source.dataset,
    source.source_code,
    source.valid_from,
    source.valid_to,
    region.id,
    seed.level,
    seed.provider_name,
    verification.official_source_url,
    TIMESTAMPTZ '2026-09-27 18:30:00+09',
    verification.verified_by,
    verification.verified_at,
    'ACTIVE'
FROM onmaru_datalab_region_seed seed
JOIN onmaru.catalog_regions region ON region.code = seed.internal_code
JOIN onmaru.catalog_region_source_codes source
  ON source.region_id = region.id
 AND source.provider = 'KTO_DATALAB'
 AND source.dataset = 'visitor'
 AND source.source_code = seed.level::text || ':' || seed.provider_code
 AND source.valid_from = DATE '2026-09-26'
JOIN onmaru.catalog_region_source_code_verifications verification
  ON verification.provider = source.provider
 AND verification.dataset = source.dataset
 AND verification.source_code = source.source_code
 AND verification.valid_from = source.valid_from
ON CONFLICT (provider, dataset, source_code, valid_from) DO NOTHING;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '032', 'DATALAB_NATIONWIDE_REGISTRY', 444,
    'Nationwide verified DataLab SIDO and SIGUNGU mapping registry'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

