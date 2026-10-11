package com.yrootlab.onmaru.web.discovery;

import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryProjection;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Filters;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.persistence.catalog.discovery.JdbcDiscoveryReadStore;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.cursor.CursorPayload;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

@Tag(name="전통문화 탐색",description="별도 승인 게시 revision의 공개 장소 조회")
@RestController
@ConditionalOnProperty(name="onmaru.discovery.api.enabled",havingValue="true")
public final class DiscoveryController {
    private static final String COOKIE="__Host-onmaru-session";
    private static final Pattern CODE=Pattern.compile("[A-Za-z0-9_:-]{1,100}");
    private static final Pattern PLACE_ID=Pattern.compile("p-[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final String SCOPE="discovery-places-v1";
    private final JdbcDiscoveryReadStore store;
    private final CursorCodec cursors;
    private final MemberLifecycleService members;
    private final Clock clock;
    DiscoveryController(JdbcDiscoveryReadStore store,CursorCodec cursors,MemberLifecycleService members,Clock clock) {
        this.store=store;this.cursors=cursors;this.members=members;this.clock=clock;
    }
    @Operation(summary="전통문화 탐색 주제 조회")
    @ApiResponses({@ApiResponse(responseCode="200"),@ApiResponse(responseCode="503")})
    @GetMapping("/api/v1/discovery/topics")
    ResponseEntity<?> topics(@CookieValue(name=COOKIE,required=false) String session,HttpServletRequest request) {
        try {
            var snapshot=store.load(null,memberId(session));
            if(snapshot==null) return unavailable(request);
            return ok(Map.of("schemaVersion",DiscoveryQuery.VERSION,"catalogRevision",snapshot.revision().toString(),
                    "countsAsOf",snapshot.countsAsOf().toString(),"items",DiscoveryProjection.topics(snapshot)));
        } catch(IllegalStateException failure) {return unavailable(request);}
          catch(RuntimeException failure) {return error(request,HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR",Map.of());}
    }
    @Operation(summary="승인된 탐색 장소 목록·facet 조회")
    @ApiResponses({@ApiResponse(responseCode="200"),@ApiResponse(responseCode="400"),@ApiResponse(responseCode="410"),@ApiResponse(responseCode="503")})
    @GetMapping("/api/v1/discovery/places")
    ResponseEntity<?> places(@RequestParam(required=false) String topic,@RequestParam(required=false) String regionCode,
            @RequestParam(required=false) String placeRole,@RequestParam(required=false) String workId,
            @RequestParam(required=false) String type,@RequestParam(required=false) String artistId,
            @RequestParam(required=false) String workTagCodes,@RequestParam(required=false) String relationTagCodes,
            @RequestParam(required=false) String odiiLinked,@RequestParam(required=false,defaultValue="RELEVANCE") String sort,
            @RequestParam(required=false,defaultValue="20") String limit,@RequestParam(required=false) String cursor,
            @CookieValue(name=COOKIE,required=false) String session,HttpServletRequest request) {
        try {
            var filter=validate(topic,regionCode,placeRole,workId,type,artistId,workTagCodes,relationTagCodes,odiiLinked,sort,limit);
            var context=context(filter);
            UUID revision=null;String after=null;
            if(cursor!=null) {
                var payload=cursors.decode(cursor,SCOPE);
                var claims=payload.claims();
                if(!context.equals(claims.get("context"))) throw new CursorInvalidException();
                try {revision=UUID.fromString((String)claims.get("revision"));after=(String)claims.get("after");}
                catch(RuntimeException failure) {throw new CursorInvalidException(failure);}
            }
            var snapshot=store.load(revision,memberId(session));
            if(snapshot==null) return revision==null?unavailable(request):error(request,HttpStatus.GONE,"CURSOR_EXPIRED",Map.of());
            var matching=DiscoveryProjection.ordered(DiscoveryProjection.matching(snapshot,filter,null),filter.sort());
            int start=0;
            if(after!=null) {
                while(start<matching.size()&&DiscoveryProjection.sortKey(matching.get(start),filter.sort()).compareTo(after)<=0) start++;
            }
            int end=Math.min(matching.size(),start+filter.limit());
            var page=matching.subList(start,end);
            boolean more=end<matching.size();
            String next=more?cursors.encode(new CursorPayload(SCOPE,Map.of("revision",snapshot.revision().toString(),"context",context,"after",DiscoveryProjection.sortKey(page.getLast(),filter.sort())),
                    clock.instant().plus(Duration.ofHours(24)))):null;
            var response=new LinkedHashMap<String,Object>();
            response.put("schemaVersion",DiscoveryQuery.VERSION);response.put("catalogRevision",snapshot.revision().toString());
            response.put("countsAsOf",snapshot.countsAsOf().toString());response.put("matchingPlaceCount",matching.size());
            response.put("facets",DiscoveryProjection.facets(snapshot,filter));
            response.put("items",page.stream().map(place->DiscoveryProjection.card(place,filter)).toList());
            response.put("hasMore",more);response.put("nextCursor",next);
            return ok(response);
        } catch(BadField failure) {return error(request,HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",Map.of("fieldErrors",Map.of(failure.field,failure.getMessage())));}
          catch(CursorExpiredException failure) {return error(request,HttpStatus.GONE,"CURSOR_EXPIRED",Map.of());}
          catch(CursorInvalidException failure) {return error(request,HttpStatus.BAD_REQUEST,"CURSOR_INVALID",Map.of());}
          catch(IllegalStateException failure) {return unavailable(request);}
          catch(RuntimeException failure) {return error(request,HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR",Map.of());}
    }
    @Operation(summary="승인된 탐색 장소 상세 조회")
    @ApiResponses({@ApiResponse(responseCode="200"),@ApiResponse(responseCode="400"),@ApiResponse(responseCode="404"),@ApiResponse(responseCode="503")})
    @GetMapping("/api/v1/discovery/places/{placeId}")
    ResponseEntity<?> place(@PathVariable String placeId,@CookieValue(name=COOKIE,required=false) String session,HttpServletRequest request) {
        if(!PLACE_ID.matcher(placeId).matches()) return error(request,HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",Map.of("fieldErrors",Map.of("placeId","invalid format")));
        try {
            var snapshot=store.load(null,memberId(session));
            if(snapshot==null) return unavailable(request);
            return snapshot.places().stream().filter(place->place.id().equals(placeId)).findFirst()
                    .<ResponseEntity<?>>map(place->ok(DiscoveryProjection.detail(place)))
                    .orElseGet(()->error(request,HttpStatus.NOT_FOUND,"NOT_FOUND",Map.of("resourceType","PLACE")));
        } catch(IllegalStateException failure) {return unavailable(request);}
          catch(RuntimeException failure) {return error(request,HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR",Map.of());}
    }
    private UUID memberId(String session) {return members.currentMember(session).map(member->member.id()).orElse(null);}
    private static Filters validate(String topic,String region,String role,String work,String type,String artist,
            String workTags,String relationTags,String odii,String sort,String rawLimit) {
        if(topic!=null&&!DiscoveryQuery.TOPICS.contains(topic)) throw new BadField("topic","unsupported code");
        code(region,"regionCode");code(role,"placeRole");code(work,"workId");code(artist,"artistId");
        if(role!=null&&!DiscoveryQuery.ROLE_KO.containsKey(role)) throw new BadField("placeRole","unsupported role");
        if(type!=null&&!DiscoveryQuery.WORK_TYPES.contains(type)) throw new BadField("type","unsupported type");
        if(!Set.of("RELEVANCE","NAME").contains(sort)) throw new BadField("sort","unsupported sort");
        int limit;
        try {limit=Integer.parseInt(rawLimit);}catch(NumberFormatException failure){throw new BadField("limit","must be between 1 and 50");}
        if(limit<1||limit>50) throw new BadField("limit","must be between 1 and 50");
        Boolean linked=null;
        if(odii!=null) {if(!Set.of("true","false").contains(odii)) throw new BadField("odiiLinked","must be boolean");linked=Boolean.valueOf(odii);}
        return new Filters(topic,region,role,work,type,artist,tags(workTags,"workTagCodes"),tags(relationTags,"relationTagCodes"),linked,sort,limit);
    }
    private static void code(String value,String field) {if(value!=null&&!CODE.matcher(value).matches()) throw new BadField(field,"invalid code format");}
    private static Set<String> tags(String value,String field) {
        if(value==null) return Set.of();
        var result=new java.util.LinkedHashSet<String>();
        for(var code:value.split(",",-1)) {code(code,field);result.add(code);}
        return Set.copyOf(result);
    }
    private static String context(Filters f) {
        var values=new TreeMap<String,String>();
        values.put("topic",String.valueOf(f.topic()));values.put("region",String.valueOf(f.regionCode()));
        values.put("role",String.valueOf(f.placeRole()));values.put("work",String.valueOf(f.workId()));
        values.put("type",String.valueOf(f.type()));values.put("artist",String.valueOf(f.artistId()));
        values.put("workTags",String.join(",",new java.util.TreeSet<>(f.workTagCodes())));
        values.put("relationTags",String.join(",",new java.util.TreeSet<>(f.relationTagCodes())));
        values.put("odii",String.valueOf(f.odiiLinked()));values.put("sort",f.sort());values.put("limit",String.valueOf(f.limit()));
        return values.toString();
    }
    private static ResponseEntity<Object> ok(Object body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    private ResponseEntity<?> unavailable(HttpServletRequest request) {return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After","30").cacheControl(CacheControl.noStore())
            .body(new ApiErrorResponse("1.2","SERVICE_UNAVAILABLE","Discovery data is temporarily unavailable",requestId(request),Map.of("retryAfterMs",30000)));}
    private ResponseEntity<?> error(HttpServletRequest request,HttpStatus status,String code,Map<String,Object> details) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2",code,code,requestId(request),details));
    }
    private static String requestId(HttpServletRequest request) {
        var value=request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value instanceof String id&&!id.isBlank()?id:UUID.randomUUID().toString();
    }
    private static final class BadField extends RuntimeException {
        final String field;BadField(String field,String message){super(message);this.field=field;}
    }
}
