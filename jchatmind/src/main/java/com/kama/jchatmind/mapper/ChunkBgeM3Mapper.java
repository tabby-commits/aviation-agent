package com.kama.jchatmind.mapper;

import com.kama.jchatmind.evaluation.model.VectorSearchHit;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity com.kama.jchatmind.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int updateById(ChunkBgeM3 chunkBgeM3);

    int countByKbId(@Param("kbId") String kbId);

    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<VectorSearchHit> similaritySearchWithDistance(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    /**
     * 按 kbId 批量加载 chunk 的 id + content（不读取 embedding 列，用于构建 BM25 倒排）
     */
    List<ChunkBgeM3> selectAllByKbIdLite(@Param("kbId") String kbId);

    /**
     * 按多个 id 查询 chunk（用于 BM25 召回结果回填 content）
     */
    List<ChunkBgeM3> selectByIds(@Param("ids") List<String> ids);

    /**
     * 按文档 id 查询所有 chunk id（用于文档删除时同步维护 BM25 倒排）
     */
    List<String> selectChunkIdsByDocId(@Param("docId") String docId);

    /**
     * 按文档 id 删除所有 chunk
     */
    int deleteByDocId(@Param("docId") String docId);
}
