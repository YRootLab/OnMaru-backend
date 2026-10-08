package com.yrootlab.onmaru.web.kcontents;

import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Independent public K-Contents read API. No legacy catalog or screen-hanok path is changed. */
@RestController
@ConditionalOnProperty(name="onmaru.discovery.api.enabled", havingValue="true")
public final class KContentsReadController {
    private static final Set<String> TYPES = Set.of("DRAMA","MOVIE","VARIETY","MUSIC_VIDEO");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
    private final KContentsReadStore store;
    private final KContentsCursor cursor;

    KContentsReadController(KContentsReadStore store, KContentsCursor cursor) {
        this.store=store;this.cursor=cursor;
    }

    @GetMapping("/api/v1/k-contents/works")
    public ResponseEntity<?> works(@RequestParam(required=false) String q,
                                   @RequestParam(required=false) String type,
                                   @RequestParam(required=false) String artistId,
                                   @RequestParam(required=false) String releaseYear,
                                   @RequestParam(required=false) String workTagCodes,
                                   @RequestParam(defaultValue="RELEVANCE") String sort,
                                   @RequestParam(defaultValue="20") String limit,
                                   @RequestParam(name="cursor",required=false) String pageCursor,
                                   HttpServletRequest request) {
        try {
            int size = limit(limit);
            type(type);
            if (!Set.of("RELEVANCE","TITLE").contains(sort)) throw new BadField("sort");
            String search = q == null ? null : q.trim();
            if (search != null && (search.codePoints().filter(c -> !Character.isWhitespace(c)).count()<2 || search.length()>100))
                throw new BadField("q");
            UUID artist = uuidOptional(artistId,"artistId");
            Integer year = year(releaseYear);
            List<String> tags = tags(workTagCodes);
            String filter = filter(search,type,String.valueOf(artist),String.valueOf(year),tags.toString(),sort,String.valueOf(size));
            var position = cursor.decode(pageCursor,"kcontents-works-v1",filter);
            var revision = store.revision(position==null?null:position.revision());
            if (revision==null) return unavailableOrExpired(position,request);
            var page = store.works(revision.id(),search,type,artist,year,tags,sort,size,position);
            var body = envelope(revision,page);
            body.put("matchingWorkCount",page.count());
            body.put("nextCursor",page.more()?cursor.encode("kcontents-works-v1",filter,revision.id(),page.lastKey(),page.lastId()):null);
            return ok(body);
        } catch (BadField invalid) { return bad(request,invalid.field); }
        catch (CursorExpiredException expired) { return error(request,HttpStatus.GONE,"CURSOR_EXPIRED",Map.of()); }
        catch (CursorInvalidException invalid) { return error(request,HttpStatus.BAD_REQUEST,"CURSOR_INVALID",Map.of()); }
        catch (DataAccessResourceFailureException | CannotCreateTransactionException unavailable) { return unavailable(request); }
        catch (RuntimeException internal) { return internal(request); }
    }

    @GetMapping("/api/v1/k-contents/works/{workId}")
    public ResponseEntity<?> work(@PathVariable String workId,HttpServletRequest request) {
        try {
            UUID id=uuid(workId,"workId");
            var revision=store.revision(null);
            if (revision==null) return unavailable(request);
            var result=store.work(revision.id(),id);
            return result==null?notFound(request,"WORK"):ok(result);
        } catch (BadField invalid) { return bad(request,invalid.field); }
        catch (DataAccessResourceFailureException | CannotCreateTransactionException unavailable) { return unavailable(request); }
        catch (RuntimeException internal) { return internal(request); }
    }

    @GetMapping("/api/v1/places/{placeId}/k-contents")
    public ResponseEntity<?> relations(@PathVariable String placeId,
                                       @RequestParam(required=false) String type,
                                       @RequestParam(defaultValue="20") String limit,
                                       @RequestParam(required=false) String cursor,
                                       HttpServletRequest request) {
        try {
            if (!placeId.matches("[a-z0-9][a-z0-9-]{2,159}")) throw new BadField("placeId");
            int size=limit(limit);type(type);
            String filter=filter(placeId,type,String.valueOf(size));
            var position=this.cursor.decode(cursor,"kcontents-relations-v1",filter);
            var revision=store.revision(position==null?null:position.revision());
            if (revision==null) return unavailableOrExpired(position,request);
            var page=store.relationsForPublicId(revision.id(),placeId,type,size,position);
            if (page==null) return notFound(request,"PLACE");
            var body=envelope(revision,page);
            body.put("matchingRelationCount",page.count());
            body.put("nextCursor",page.more()?this.cursor.encode("kcontents-relations-v1",filter,revision.id(),page.lastKey(),page.lastId()):null);
            return ok(body);
        } catch (BadField invalid) { return bad(request,invalid.field); }
        catch (CursorExpiredException expired) { return error(request,HttpStatus.GONE,"CURSOR_EXPIRED",Map.of()); }
        catch (CursorInvalidException invalid) { return error(request,HttpStatus.BAD_REQUEST,"CURSOR_INVALID",Map.of()); }
        catch (DataAccessResourceFailureException | CannotCreateTransactionException unavailable) { return unavailable(request); }
        catch (RuntimeException internal) { return internal(request); }
    }

    private static LinkedHashMap<String,Object> envelope(KContentsReadStore.Revision revision,KContentsReadStore.Page page) {
        var body=new LinkedHashMap<String,Object>();
        body.put("schemaVersion","1.2");body.put("catalogRevision",revision.id());body.put("countsAsOf",revision.at());
        body.put("items",page.items());body.put("hasMore",page.more());return body;
    }
    private static ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private static ResponseEntity<?> unavailableOrExpired(KContentsCursor.Position position,HttpServletRequest request) {
        return position==null?unavailable(request):error(request,HttpStatus.GONE,"CURSOR_EXPIRED",Map.of());
    }
    private static ResponseEntity<?> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After","30")
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2","SERVICE_UNAVAILABLE","Discovery read model unavailable",requestId(request),Map.of("retryAfterMs",30000)));
    }
    private static ResponseEntity<?> internal(HttpServletRequest request) {
        return error(request,HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR",Map.of());
    }
    private static ResponseEntity<?> notFound(HttpServletRequest request,String resource) {
        return error(request,HttpStatus.NOT_FOUND,"NOT_FOUND",Map.of("resourceType",resource));
    }
    private static ResponseEntity<?> bad(HttpServletRequest request,String field) {
        return error(request,HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",Map.of("fieldErrors",Map.of(field,"invalid value")));
    }
    private static ResponseEntity<?> error(HttpServletRequest request,HttpStatus status,String code,Map<String,Object> details) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2",code,code,requestId(request),details));
    }
    private static String requestId(HttpServletRequest request) {
        Object value=request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value instanceof String id && !id.isBlank()?id:UUID.randomUUID().toString();
    }
    private static int limit(String value) {
        try { int parsed=Integer.parseInt(value);if(parsed>=1&&parsed<=50)return parsed; }
        catch (NumberFormatException ignored) { }
        throw new BadField("limit");
    }
    private static void type(String value) { if(value!=null&&!TYPES.contains(value))throw new BadField("type"); }
    private static UUID uuid(String value,String field) {
        try {return UUID.fromString(value);}catch(Exception invalid){throw new BadField(field);}
    }
    private static UUID uuidOptional(String value,String field) {return value==null?null:uuid(value,field);}
    private static Integer year(String value) {
        if(value==null)return null;
        try {int number=Integer.parseInt(value);if(number>=1895&&number<=2200)return number;}
        catch(NumberFormatException ignored) { }
        throw new BadField("releaseYear");
    }
    private static List<String> tags(String raw) {
        if(raw==null)return List.of();
        var values=Arrays.stream(raw.split(",",-1)).map(String::trim).distinct().sorted().toList();
        if(values.size()>20||values.stream().anyMatch(value->!TAG.matcher(value).matches()))throw new BadField("workTagCodes");
        return values;
    }
    private static String filter(String... parts) {
        StringBuilder value=new StringBuilder();
        for (String part:parts) value.append(part==null?-1:part.length()).append(':').append(part==null?"":part);
        return value.toString();
    }
    private static final class BadField extends RuntimeException {
        final String field; BadField(String field){this.field=field;}
    }
}
