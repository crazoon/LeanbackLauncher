package com.google.android.exoplayer2.extractor.mp4;

import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.extractor.ChunkIndex;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.SeekMap.Unseekable;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.text.cea.CeaUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.TimestampAdjuster;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.UUID;

public final class FragmentedMp4Extractor
  implements Extractor
{
  public static final ExtractorsFactory FACTORY = new ExtractorsFactory()
  {
    public Extractor[] createExtractors()
    {
      return new Extractor[] { new FragmentedMp4Extractor() };
    }
  };
  public static final int FLAG_ENABLE_CEA608_TRACK = 8;
  public static final int FLAG_ENABLE_EMSG_TRACK = 4;
  private static final int FLAG_SIDELOADED = 16;
  public static final int FLAG_WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME = 1;
  public static final int FLAG_WORKAROUND_IGNORE_TFDT_BOX = 2;
  private static final int NAL_UNIT_TYPE_SEI = 6;
  private static final byte[] PIFF_SAMPLE_ENCRYPTION_BOX_EXTENDED_TYPE = { -94, 57, 79, 82, 90, -101, 79, 20, -94, 68, 108, 66, 124, 100, -115, -12 };
  private static final int SAMPLE_GROUP_TYPE_seig = Util.getIntegerCodeForString("seig");
  private static final int STATE_READING_ATOM_HEADER = 0;
  private static final int STATE_READING_ATOM_PAYLOAD = 1;
  private static final int STATE_READING_ENCRYPTION_DATA = 2;
  private static final int STATE_READING_SAMPLE_CONTINUE = 4;
  private static final int STATE_READING_SAMPLE_START = 3;
  private static final String TAG = "FragmentedMp4Extractor";
  private ParsableByteArray atomData;
  private final ParsableByteArray atomHeader;
  private int atomHeaderBytesRead;
  private long atomSize;
  private int atomType;
  private TrackOutput cea608TrackOutput;
  private final Stack<Atom.ContainerAtom> containerAtoms;
  private TrackBundle currentTrackBundle;
  private long durationUs;
  private final ParsableByteArray encryptionSignalByte;
  private long endOfMdatPosition;
  private TrackOutput eventMessageTrackOutput;
  private final byte[] extendedTypeScratch;
  private ExtractorOutput extractorOutput;
  private final int flags;
  private boolean haveOutputSeekMap;
  private final ParsableByteArray nalLength;
  private final ParsableByteArray nalPayload;
  private final ParsableByteArray nalStartCode;
  private int parserState;
  private int pendingMetadataSampleBytes;
  private final LinkedList<MetadataSampleInfo> pendingMetadataSampleInfos;
  private int sampleBytesWritten;
  private int sampleCurrentNalBytesRemaining;
  private int sampleSize;
  private long segmentIndexEarliestPresentationTimeUs;
  private final Track sideloadedTrack;
  private final TimestampAdjuster timestampAdjuster;
  private final SparseArray<TrackBundle> trackBundles;
  
  public FragmentedMp4Extractor()
  {
    this(0, null);
  }
  
  public FragmentedMp4Extractor(int paramInt, Track paramTrack, TimestampAdjuster paramTimestampAdjuster)
  {
    this.sideloadedTrack = paramTrack;
    if (paramTrack != null) {}
    for (int i = 16;; i = 0)
    {
      this.flags = (i | paramInt);
      this.timestampAdjuster = paramTimestampAdjuster;
      this.atomHeader = new ParsableByteArray(16);
      this.nalStartCode = new ParsableByteArray(NalUnitUtil.NAL_START_CODE);
      this.nalLength = new ParsableByteArray(4);
      this.nalPayload = new ParsableByteArray(1);
      this.encryptionSignalByte = new ParsableByteArray(1);
      this.extendedTypeScratch = new byte[16];
      this.containerAtoms = new Stack();
      this.pendingMetadataSampleInfos = new LinkedList();
      this.trackBundles = new SparseArray();
      this.durationUs = -9223372036854775807L;
      this.segmentIndexEarliestPresentationTimeUs = -9223372036854775807L;
      enterReadingAtomHeaderState();
      return;
    }
  }
  
  public FragmentedMp4Extractor(int paramInt, TimestampAdjuster paramTimestampAdjuster)
  {
    this(paramInt, null, paramTimestampAdjuster);
  }
  
  private int appendSampleEncryptionData(TrackBundle paramTrackBundle)
  {
    TrackFragment localTrackFragment = paramTrackBundle.fragment;
    ParsableByteArray localParsableByteArray = localTrackFragment.sampleEncryptionData;
    int i = localTrackFragment.header.sampleDescriptionIndex;
    Object localObject;
    int j;
    int k;
    if (localTrackFragment.trackEncryptionBox != null)
    {
      localObject = localTrackFragment.trackEncryptionBox;
      j = ((TrackEncryptionBox)localObject).initializationVectorSize;
      k = localTrackFragment.sampleHasSubsampleEncryptionTable[paramTrackBundle.currentSampleIndex];
      localObject = this.encryptionSignalByte.data;
      if (k == 0) {
        break label137;
      }
    }
    label137:
    for (i = 128;; i = 0)
    {
      localObject[0] = ((byte)(i | j));
      this.encryptionSignalByte.setPosition(0);
      paramTrackBundle = paramTrackBundle.output;
      paramTrackBundle.sampleData(this.encryptionSignalByte, 1);
      paramTrackBundle.sampleData(localParsableByteArray, j);
      if (k != 0) {
        break label142;
      }
      return j + 1;
      localObject = paramTrackBundle.track.sampleDescriptionEncryptionBoxes[i];
      break;
    }
    label142:
    i = localParsableByteArray.readUnsignedShort();
    localParsableByteArray.skipBytes(-2);
    i = i * 6 + 2;
    paramTrackBundle.sampleData(localParsableByteArray, i);
    return j + 1 + i;
  }
  
  private void enterReadingAtomHeaderState()
  {
    this.parserState = 0;
    this.atomHeaderBytesRead = 0;
  }
  
  private static DrmInitData getDrmInitDataFromAtoms(List<Atom.LeafAtom> paramList)
  {
    Object localObject2 = null;
    int j = paramList.size();
    int i = 0;
    if (i < j)
    {
      Object localObject3 = (Atom.LeafAtom)paramList.get(i);
      Object localObject1 = localObject2;
      if (((Atom.LeafAtom)localObject3).type == Atom.TYPE_pssh)
      {
        localObject1 = localObject2;
        if (localObject2 == null) {
          localObject1 = new ArrayList();
        }
        localObject2 = ((Atom.LeafAtom)localObject3).data.data;
        localObject3 = PsshAtomUtil.parseUuid((byte[])localObject2);
        if (localObject3 != null) {
          break label100;
        }
        Log.w("FragmentedMp4Extractor", "Skipped pssh atom (failed to extract uuid)");
      }
      for (;;)
      {
        i += 1;
        localObject2 = localObject1;
        break;
        label100:
        ((ArrayList)localObject1).add(new DrmInitData.SchemeData((UUID)localObject3, "video/mp4", (byte[])localObject2));
      }
    }
    if (localObject2 == null) {
      return null;
    }
    return new DrmInitData((List)localObject2);
  }
  
  private static TrackBundle getNextFragmentRun(SparseArray<TrackBundle> paramSparseArray)
  {
    Object localObject = null;
    long l1 = Long.MAX_VALUE;
    int j = paramSparseArray.size();
    int i = 0;
    if (i < j)
    {
      TrackBundle localTrackBundle = (TrackBundle)paramSparseArray.valueAt(i);
      long l2;
      if (localTrackBundle.currentTrackRunIndex == localTrackBundle.fragment.trunCount) {
        l2 = l1;
      }
      for (;;)
      {
        i += 1;
        l1 = l2;
        break;
        long l3 = localTrackBundle.fragment.trunDataPosition[localTrackBundle.currentTrackRunIndex];
        l2 = l1;
        if (l3 < l1)
        {
          localObject = localTrackBundle;
          l2 = l3;
        }
      }
    }
    return (TrackBundle)localObject;
  }
  
  private void maybeInitExtraTracks()
  {
    if (((this.flags & 0x4) != 0) && (this.eventMessageTrackOutput == null))
    {
      this.eventMessageTrackOutput = this.extractorOutput.track(this.trackBundles.size());
      this.eventMessageTrackOutput.format(Format.createSampleFormat(null, "application/x-emsg", Long.MAX_VALUE));
    }
    if (((this.flags & 0x8) != 0) && (this.cea608TrackOutput == null))
    {
      this.cea608TrackOutput = this.extractorOutput.track(this.trackBundles.size() + 1);
      this.cea608TrackOutput.format(Format.createTextSampleFormat(null, "application/cea-608", null, -1, 0, null, null));
    }
  }
  
  private void onContainerAtomRead(Atom.ContainerAtom paramContainerAtom)
    throws ParserException
  {
    if (paramContainerAtom.type == Atom.TYPE_moov) {
      onMoovContainerAtomRead(paramContainerAtom);
    }
    do
    {
      return;
      if (paramContainerAtom.type == Atom.TYPE_moof)
      {
        onMoofContainerAtomRead(paramContainerAtom);
        return;
      }
    } while (this.containerAtoms.isEmpty());
    ((Atom.ContainerAtom)this.containerAtoms.peek()).add(paramContainerAtom);
  }
  
  private void onEmsgLeafAtomRead(ParsableByteArray paramParsableByteArray)
  {
    if (this.eventMessageTrackOutput == null) {
      return;
    }
    paramParsableByteArray.setPosition(12);
    paramParsableByteArray.readNullTerminatedString();
    paramParsableByteArray.readNullTerminatedString();
    long l = paramParsableByteArray.readUnsignedInt();
    l = Util.scaleLargeTimestamp(paramParsableByteArray.readUnsignedInt(), 1000000L, l);
    paramParsableByteArray.setPosition(12);
    int i = paramParsableByteArray.bytesLeft();
    this.eventMessageTrackOutput.sampleData(paramParsableByteArray, i);
    if (this.segmentIndexEarliestPresentationTimeUs != -9223372036854775807L)
    {
      this.eventMessageTrackOutput.sampleMetadata(this.segmentIndexEarliestPresentationTimeUs + l, 1, i, 0, null);
      return;
    }
    this.pendingMetadataSampleInfos.addLast(new MetadataSampleInfo(l, i));
    this.pendingMetadataSampleBytes += i;
  }
  
  private void onLeafAtomRead(Atom.LeafAtom paramLeafAtom, long paramLong)
    throws ParserException
  {
    if (!this.containerAtoms.isEmpty()) {
      ((Atom.ContainerAtom)this.containerAtoms.peek()).add(paramLeafAtom);
    }
    do
    {
      return;
      if (paramLeafAtom.type == Atom.TYPE_sidx)
      {
        paramLeafAtom = parseSidx(paramLeafAtom.data, paramLong);
        this.segmentIndexEarliestPresentationTimeUs = ((Long)paramLeafAtom.first).longValue();
        this.extractorOutput.seekMap((SeekMap)paramLeafAtom.second);
        this.haveOutputSeekMap = true;
        return;
      }
    } while (paramLeafAtom.type != Atom.TYPE_emsg);
    onEmsgLeafAtomRead(paramLeafAtom.data);
  }
  
  private void onMoofContainerAtomRead(Atom.ContainerAtom paramContainerAtom)
    throws ParserException
  {
    parseMoof(paramContainerAtom, this.trackBundles, this.flags, this.extendedTypeScratch);
    paramContainerAtom = getDrmInitDataFromAtoms(paramContainerAtom.leafChildren);
    if (paramContainerAtom != null)
    {
      int j = this.trackBundles.size();
      int i = 0;
      while (i < j)
      {
        ((TrackBundle)this.trackBundles.valueAt(i)).updateDrmInitData(paramContainerAtom);
        i += 1;
      }
    }
  }
  
  private void onMoovContainerAtomRead(Atom.ContainerAtom paramContainerAtom)
    throws ParserException
  {
    Object localObject1;
    SparseArray localSparseArray;
    long l;
    label63:
    Object localObject3;
    if (this.sideloadedTrack == null)
    {
      bool = true;
      Assertions.checkState(bool, "Unexpected moov box.");
      localObject1 = getDrmInitDataFromAtoms(paramContainerAtom.leafChildren);
      localObject2 = paramContainerAtom.getContainerAtomOfType(Atom.TYPE_mvex);
      localSparseArray = new SparseArray();
      l = -9223372036854775807L;
      j = ((Atom.ContainerAtom)localObject2).leafChildren.size();
      i = 0;
      if (i >= j) {
        break label163;
      }
      localObject3 = (Atom.LeafAtom)((Atom.ContainerAtom)localObject2).leafChildren.get(i);
      if (((Atom.LeafAtom)localObject3).type != Atom.TYPE_trex) {
        break label139;
      }
      localObject3 = parseTrex(((Atom.LeafAtom)localObject3).data);
      localSparseArray.put(((Integer)((Pair)localObject3).first).intValue(), ((Pair)localObject3).second);
    }
    for (;;)
    {
      i += 1;
      break label63;
      bool = false;
      break;
      label139:
      if (((Atom.LeafAtom)localObject3).type == Atom.TYPE_mehd) {
        l = parseMehd(((Atom.LeafAtom)localObject3).data);
      }
    }
    label163:
    Object localObject2 = new SparseArray();
    int j = paramContainerAtom.containerChildren.size();
    int i = 0;
    while (i < j)
    {
      localObject3 = (Atom.ContainerAtom)paramContainerAtom.containerChildren.get(i);
      if (((Atom.ContainerAtom)localObject3).type == Atom.TYPE_trak)
      {
        localObject3 = AtomParsers.parseTrak((Atom.ContainerAtom)localObject3, paramContainerAtom.getLeafAtomOfType(Atom.TYPE_mvhd), l, (DrmInitData)localObject1, false);
        if (localObject3 != null) {
          ((SparseArray)localObject2).put(((Track)localObject3).id, localObject3);
        }
      }
      i += 1;
    }
    j = ((SparseArray)localObject2).size();
    if (this.trackBundles.size() == 0)
    {
      i = 0;
      while (i < j)
      {
        paramContainerAtom = (Track)((SparseArray)localObject2).valueAt(i);
        localObject1 = new TrackBundle(this.extractorOutput.track(i));
        ((TrackBundle)localObject1).init(paramContainerAtom, (DefaultSampleValues)localSparseArray.get(paramContainerAtom.id));
        this.trackBundles.put(paramContainerAtom.id, localObject1);
        this.durationUs = Math.max(this.durationUs, paramContainerAtom.durationUs);
        i += 1;
      }
      maybeInitExtraTracks();
      this.extractorOutput.endTracks();
      return;
    }
    if (this.trackBundles.size() == j) {}
    for (boolean bool = true;; bool = false)
    {
      Assertions.checkState(bool);
      i = 0;
      while (i < j)
      {
        paramContainerAtom = (Track)((SparseArray)localObject2).valueAt(i);
        ((TrackBundle)this.trackBundles.get(paramContainerAtom.id)).init(paramContainerAtom, (DefaultSampleValues)localSparseArray.get(paramContainerAtom.id));
        i += 1;
      }
      break;
    }
  }
  
  private static long parseMehd(ParsableByteArray paramParsableByteArray)
  {
    paramParsableByteArray.setPosition(8);
    if (Atom.parseFullAtomVersion(paramParsableByteArray.readInt()) == 0) {
      return paramParsableByteArray.readUnsignedInt();
    }
    return paramParsableByteArray.readUnsignedLongToLong();
  }
  
  private static void parseMoof(Atom.ContainerAtom paramContainerAtom, SparseArray<TrackBundle> paramSparseArray, int paramInt, byte[] paramArrayOfByte)
    throws ParserException
  {
    int j = paramContainerAtom.containerChildren.size();
    int i = 0;
    while (i < j)
    {
      Atom.ContainerAtom localContainerAtom = (Atom.ContainerAtom)paramContainerAtom.containerChildren.get(i);
      if (localContainerAtom.type == Atom.TYPE_traf) {
        parseTraf(localContainerAtom, paramSparseArray, paramInt, paramArrayOfByte);
      }
      i += 1;
    }
  }
  
  private static void parseSaio(ParsableByteArray paramParsableByteArray, TrackFragment paramTrackFragment)
    throws ParserException
  {
    paramParsableByteArray.setPosition(8);
    int i = paramParsableByteArray.readInt();
    if ((Atom.parseFullAtomFlags(i) & 0x1) == 1) {
      paramParsableByteArray.skipBytes(8);
    }
    int j = paramParsableByteArray.readUnsignedIntToInt();
    if (j != 1) {
      throw new ParserException("Unexpected saio entry count: " + j);
    }
    i = Atom.parseFullAtomVersion(i);
    long l2 = paramTrackFragment.auxiliaryDataPosition;
    if (i == 0) {}
    for (long l1 = paramParsableByteArray.readUnsignedInt();; l1 = paramParsableByteArray.readUnsignedLongToLong())
    {
      paramTrackFragment.auxiliaryDataPosition = (l1 + l2);
      return;
    }
  }
  
  private static void parseSaiz(TrackEncryptionBox paramTrackEncryptionBox, ParsableByteArray paramParsableByteArray, TrackFragment paramTrackFragment)
    throws ParserException
  {
    int n = paramTrackEncryptionBox.initializationVectorSize;
    paramParsableByteArray.setPosition(8);
    if ((Atom.parseFullAtomFlags(paramParsableByteArray.readInt()) & 0x1) == 1) {
      paramParsableByteArray.skipBytes(8);
    }
    int j = paramParsableByteArray.readUnsignedByte();
    int m = paramParsableByteArray.readUnsignedIntToInt();
    if (m != paramTrackFragment.sampleCount) {
      throw new ParserException("Length mismatch: " + m + ", " + paramTrackFragment.sampleCount);
    }
    int i = 0;
    int k;
    if (j == 0)
    {
      paramTrackEncryptionBox = paramTrackFragment.sampleHasSubsampleEncryptionTable;
      j = 0;
      k = i;
      if (j < m)
      {
        k = paramParsableByteArray.readUnsignedByte();
        i += k;
        if (k > n) {}
        for (bool = true;; bool = false)
        {
          paramTrackEncryptionBox[j] = bool;
          j += 1;
          break;
        }
      }
    }
    else
    {
      if (j <= n) {
        break label199;
      }
    }
    label199:
    for (boolean bool = true;; bool = false)
    {
      k = 0 + j * m;
      Arrays.fill(paramTrackFragment.sampleHasSubsampleEncryptionTable, 0, m, bool);
      paramTrackFragment.initEncryptionData(k);
      return;
    }
  }
  
  private static void parseSenc(ParsableByteArray paramParsableByteArray, int paramInt, TrackFragment paramTrackFragment)
    throws ParserException
  {
    paramParsableByteArray.setPosition(paramInt + 8);
    paramInt = Atom.parseFullAtomFlags(paramParsableByteArray.readInt());
    if ((paramInt & 0x1) != 0) {
      throw new ParserException("Overriding TrackEncryptionBox parameters is unsupported.");
    }
    if ((paramInt & 0x2) != 0) {}
    for (boolean bool = true;; bool = false)
    {
      paramInt = paramParsableByteArray.readUnsignedIntToInt();
      if (paramInt == paramTrackFragment.sampleCount) {
        break;
      }
      throw new ParserException("Length mismatch: " + paramInt + ", " + paramTrackFragment.sampleCount);
    }
    Arrays.fill(paramTrackFragment.sampleHasSubsampleEncryptionTable, 0, paramInt, bool);
    paramTrackFragment.initEncryptionData(paramParsableByteArray.bytesLeft());
    paramTrackFragment.fillEncryptionData(paramParsableByteArray);
  }
  
  private static void parseSenc(ParsableByteArray paramParsableByteArray, TrackFragment paramTrackFragment)
    throws ParserException
  {
    parseSenc(paramParsableByteArray, 0, paramTrackFragment);
  }
  
  private static void parseSgpd(ParsableByteArray paramParsableByteArray1, ParsableByteArray paramParsableByteArray2, TrackFragment paramTrackFragment)
    throws ParserException
  {
    paramParsableByteArray1.setPosition(8);
    int i = paramParsableByteArray1.readInt();
    if (paramParsableByteArray1.readInt() != SAMPLE_GROUP_TYPE_seig) {}
    for (;;)
    {
      return;
      if (Atom.parseFullAtomVersion(i) == 1) {
        paramParsableByteArray1.skipBytes(4);
      }
      if (paramParsableByteArray1.readInt() != 1) {
        throw new ParserException("Entry count in sbgp != 1 (unsupported).");
      }
      paramParsableByteArray2.setPosition(8);
      i = paramParsableByteArray2.readInt();
      if (paramParsableByteArray2.readInt() == SAMPLE_GROUP_TYPE_seig)
      {
        i = Atom.parseFullAtomVersion(i);
        if (i == 1)
        {
          if (paramParsableByteArray2.readUnsignedInt() == 0L) {
            throw new ParserException("Variable length decription in sgpd found (unsupported)");
          }
        }
        else if (i >= 2) {
          paramParsableByteArray2.skipBytes(4);
        }
        if (paramParsableByteArray2.readUnsignedInt() != 1L) {
          throw new ParserException("Entry count in sgpd != 1 (unsupported).");
        }
        paramParsableByteArray2.skipBytes(2);
        if (paramParsableByteArray2.readUnsignedByte() == 1) {}
        for (boolean bool = true; bool; bool = false)
        {
          i = paramParsableByteArray2.readUnsignedByte();
          paramParsableByteArray1 = new byte[16];
          paramParsableByteArray2.readBytes(paramParsableByteArray1, 0, paramParsableByteArray1.length);
          paramTrackFragment.definesEncryptionData = true;
          paramTrackFragment.trackEncryptionBox = new TrackEncryptionBox(bool, i, paramParsableByteArray1);
          return;
        }
      }
    }
  }
  
  private static Pair<Long, ChunkIndex> parseSidx(ParsableByteArray paramParsableByteArray, long paramLong)
    throws ParserException
  {
    paramParsableByteArray.setPosition(8);
    int i = Atom.parseFullAtomVersion(paramParsableByteArray.readInt());
    paramParsableByteArray.skipBytes(4);
    long l4 = paramParsableByteArray.readUnsignedInt();
    long l1;
    long l3;
    int j;
    int[] arrayOfInt;
    long[] arrayOfLong1;
    long[] arrayOfLong2;
    long[] arrayOfLong3;
    long l2;
    if (i == 0)
    {
      l1 = paramParsableByteArray.readUnsignedInt();
      paramLong += paramParsableByteArray.readUnsignedInt();
      l3 = Util.scaleLargeTimestamp(l1, 1000000L, l4);
      paramParsableByteArray.skipBytes(2);
      j = paramParsableByteArray.readUnsignedShort();
      arrayOfInt = new int[j];
      arrayOfLong1 = new long[j];
      arrayOfLong2 = new long[j];
      arrayOfLong3 = new long[j];
      l2 = l3;
      i = 0;
    }
    for (;;)
    {
      if (i >= j) {
        break label220;
      }
      int k = paramParsableByteArray.readInt();
      if ((0x80000000 & k) != 0)
      {
        throw new ParserException("Unhandled indirect reference");
        l1 = paramParsableByteArray.readUnsignedLongToLong();
        paramLong += paramParsableByteArray.readUnsignedLongToLong();
        break;
      }
      long l5 = paramParsableByteArray.readUnsignedInt();
      arrayOfInt[i] = (0x7FFFFFFF & k);
      arrayOfLong1[i] = paramLong;
      arrayOfLong3[i] = l2;
      l1 += l5;
      l2 = Util.scaleLargeTimestamp(l1, 1000000L, l4);
      arrayOfLong2[i] = (l2 - arrayOfLong3[i]);
      paramParsableByteArray.skipBytes(4);
      paramLong += arrayOfInt[i];
      i += 1;
    }
    label220:
    return Pair.create(Long.valueOf(l3), new ChunkIndex(arrayOfInt, arrayOfLong1, arrayOfLong2, arrayOfLong3));
  }
  
  private static long parseTfdt(ParsableByteArray paramParsableByteArray)
  {
    paramParsableByteArray.setPosition(8);
    if (Atom.parseFullAtomVersion(paramParsableByteArray.readInt()) == 1) {
      return paramParsableByteArray.readUnsignedLongToLong();
    }
    return paramParsableByteArray.readUnsignedInt();
  }
  
  private static TrackBundle parseTfhd(ParsableByteArray paramParsableByteArray, SparseArray<TrackBundle> paramSparseArray, int paramInt)
  {
    paramParsableByteArray.setPosition(8);
    int k = Atom.parseFullAtomFlags(paramParsableByteArray.readInt());
    int i = paramParsableByteArray.readInt();
    if ((paramInt & 0x10) == 0) {}
    for (paramInt = i;; paramInt = 0)
    {
      paramSparseArray = (TrackBundle)paramSparseArray.get(paramInt);
      if (paramSparseArray != null) {
        break;
      }
      return null;
    }
    if ((k & 0x1) != 0)
    {
      long l = paramParsableByteArray.readUnsignedLongToLong();
      paramSparseArray.fragment.dataPosition = l;
      paramSparseArray.fragment.auxiliaryDataPosition = l;
    }
    DefaultSampleValues localDefaultSampleValues = paramSparseArray.defaultSampleValues;
    label113:
    int j;
    if ((k & 0x2) != 0)
    {
      paramInt = paramParsableByteArray.readUnsignedIntToInt() - 1;
      if ((k & 0x8) == 0) {
        break label172;
      }
      i = paramParsableByteArray.readUnsignedIntToInt();
      if ((k & 0x10) == 0) {
        break label181;
      }
      j = paramParsableByteArray.readUnsignedIntToInt();
      label127:
      if ((k & 0x20) == 0) {
        break label191;
      }
    }
    label172:
    label181:
    label191:
    for (k = paramParsableByteArray.readUnsignedIntToInt();; k = localDefaultSampleValues.flags)
    {
      paramSparseArray.fragment.header = new DefaultSampleValues(paramInt, i, j, k);
      return paramSparseArray;
      paramInt = localDefaultSampleValues.sampleDescriptionIndex;
      break;
      i = localDefaultSampleValues.duration;
      break label113;
      j = localDefaultSampleValues.size;
      break label127;
    }
  }
  
  private static void parseTraf(Atom.ContainerAtom paramContainerAtom, SparseArray<TrackBundle> paramSparseArray, int paramInt, byte[] paramArrayOfByte)
    throws ParserException
  {
    Object localObject = parseTfhd(paramContainerAtom.getLeafAtomOfType(Atom.TYPE_tfhd).data, paramSparseArray, paramInt);
    if (localObject == null) {}
    for (;;)
    {
      return;
      paramSparseArray = ((TrackBundle)localObject).fragment;
      long l2 = paramSparseArray.nextFragmentDecodeTime;
      ((TrackBundle)localObject).reset();
      long l1 = l2;
      if (paramContainerAtom.getLeafAtomOfType(Atom.TYPE_tfdt) != null)
      {
        l1 = l2;
        if ((paramInt & 0x2) == 0) {
          l1 = parseTfdt(paramContainerAtom.getLeafAtomOfType(Atom.TYPE_tfdt).data);
        }
      }
      parseTruns(paramContainerAtom, (TrackBundle)localObject, l1, paramInt);
      Atom.LeafAtom localLeafAtom = paramContainerAtom.getLeafAtomOfType(Atom.TYPE_saiz);
      if (localLeafAtom != null) {
        parseSaiz(localObject.track.sampleDescriptionEncryptionBoxes[paramSparseArray.header.sampleDescriptionIndex], localLeafAtom.data, paramSparseArray);
      }
      localObject = paramContainerAtom.getLeafAtomOfType(Atom.TYPE_saio);
      if (localObject != null) {
        parseSaio(((Atom.LeafAtom)localObject).data, paramSparseArray);
      }
      localObject = paramContainerAtom.getLeafAtomOfType(Atom.TYPE_senc);
      if (localObject != null) {
        parseSenc(((Atom.LeafAtom)localObject).data, paramSparseArray);
      }
      localObject = paramContainerAtom.getLeafAtomOfType(Atom.TYPE_sbgp);
      localLeafAtom = paramContainerAtom.getLeafAtomOfType(Atom.TYPE_sgpd);
      if ((localObject != null) && (localLeafAtom != null)) {
        parseSgpd(((Atom.LeafAtom)localObject).data, localLeafAtom.data, paramSparseArray);
      }
      int i = paramContainerAtom.leafChildren.size();
      paramInt = 0;
      while (paramInt < i)
      {
        localObject = (Atom.LeafAtom)paramContainerAtom.leafChildren.get(paramInt);
        if (((Atom.LeafAtom)localObject).type == Atom.TYPE_uuid) {
          parseUuid(((Atom.LeafAtom)localObject).data, paramSparseArray, paramArrayOfByte);
        }
        paramInt += 1;
      }
    }
  }
  
  private static Pair<Integer, DefaultSampleValues> parseTrex(ParsableByteArray paramParsableByteArray)
  {
    paramParsableByteArray.setPosition(12);
    return Pair.create(Integer.valueOf(paramParsableByteArray.readInt()), new DefaultSampleValues(paramParsableByteArray.readUnsignedIntToInt() - 1, paramParsableByteArray.readUnsignedIntToInt(), paramParsableByteArray.readUnsignedIntToInt(), paramParsableByteArray.readInt()));
  }
  
  private static int parseTrun(TrackBundle paramTrackBundle, int paramInt1, long paramLong, int paramInt2, ParsableByteArray paramParsableByteArray, int paramInt3)
  {
    paramParsableByteArray.setPosition(8);
    int i1 = Atom.parseFullAtomFlags(paramParsableByteArray.readInt());
    Track localTrack = paramTrackBundle.track;
    paramTrackBundle = paramTrackBundle.fragment;
    DefaultSampleValues localDefaultSampleValues = paramTrackBundle.header;
    paramTrackBundle.trunLength[paramInt1] = paramParsableByteArray.readUnsignedIntToInt();
    paramTrackBundle.trunDataPosition[paramInt1] = paramTrackBundle.dataPosition;
    Object localObject;
    if ((i1 & 0x1) != 0)
    {
      localObject = paramTrackBundle.trunDataPosition;
      localObject[paramInt1] += paramParsableByteArray.readInt();
    }
    int j;
    int k;
    label124:
    int m;
    label136:
    int n;
    label148:
    label160:
    int[] arrayOfInt;
    boolean[] arrayOfBoolean;
    label268:
    int i4;
    label295:
    int i2;
    label314:
    int i3;
    if ((i1 & 0x4) != 0)
    {
      j = 1;
      int i = localDefaultSampleValues.flags;
      if (j != 0) {
        i = paramParsableByteArray.readUnsignedIntToInt();
      }
      if ((i1 & 0x100) == 0) {
        break label437;
      }
      k = 1;
      if ((i1 & 0x200) == 0) {
        break label443;
      }
      m = 1;
      if ((i1 & 0x400) == 0) {
        break label449;
      }
      n = 1;
      if ((i1 & 0x800) == 0) {
        break label455;
      }
      i1 = 1;
      long l2 = 0L;
      long l1 = l2;
      if (localTrack.editListDurations != null)
      {
        l1 = l2;
        if (localTrack.editListDurations.length == 1)
        {
          l1 = l2;
          if (localTrack.editListDurations[0] == 0L) {
            l1 = Util.scaleLargeTimestamp(localTrack.editListMediaTimes[0], 1000L, localTrack.timescale);
          }
        }
      }
      localObject = paramTrackBundle.sampleSizeTable;
      arrayOfInt = paramTrackBundle.sampleCompositionTimeOffsetTable;
      long[] arrayOfLong = paramTrackBundle.sampleDecodingTimeTable;
      arrayOfBoolean = paramTrackBundle.sampleIsSyncFrameTable;
      if ((localTrack.type != 2) || ((paramInt2 & 0x1) == 0)) {
        break label461;
      }
      paramInt2 = 1;
      i4 = paramInt3 + paramTrackBundle.trunLength[paramInt1];
      l2 = localTrack.timescale;
      if (paramInt1 <= 0) {
        break label467;
      }
      paramLong = paramTrackBundle.nextFragmentDecodeTime;
      if (paramInt3 >= i4) {
        break label528;
      }
      if (k == 0) {
        break label470;
      }
      i2 = paramParsableByteArray.readUnsignedIntToInt();
      if (m == 0) {
        break label480;
      }
      i3 = paramParsableByteArray.readUnsignedIntToInt();
      label326:
      if ((paramInt3 != 0) || (j == 0)) {
        break label490;
      }
      paramInt1 = i;
      label339:
      if (i1 == 0) {
        break label513;
      }
      arrayOfInt[paramInt3] = ((int)(paramParsableByteArray.readInt() * 1000 / l2));
      label363:
      arrayOfLong[paramInt3] = (Util.scaleLargeTimestamp(paramLong, 1000L, l2) - l1);
      localObject[paramInt3] = i3;
      if (((paramInt1 >> 16 & 0x1) != 0) || ((paramInt2 != 0) && (paramInt3 != 0))) {
        break label522;
      }
    }
    label437:
    label443:
    label449:
    label455:
    label461:
    label467:
    label470:
    label480:
    label490:
    label513:
    label522:
    for (int i5 = 1;; i5 = 0)
    {
      arrayOfBoolean[paramInt3] = i5;
      paramLong += i2;
      paramInt3 += 1;
      break label295;
      j = 0;
      break;
      k = 0;
      break label124;
      m = 0;
      break label136;
      n = 0;
      break label148;
      i1 = 0;
      break label160;
      paramInt2 = 0;
      break label268;
      break label295;
      i2 = localDefaultSampleValues.duration;
      break label314;
      i3 = localDefaultSampleValues.size;
      break label326;
      if (n != 0)
      {
        paramInt1 = paramParsableByteArray.readInt();
        break label339;
      }
      paramInt1 = localDefaultSampleValues.flags;
      break label339;
      arrayOfInt[paramInt3] = 0;
      break label363;
    }
    label528:
    paramTrackBundle.nextFragmentDecodeTime = paramLong;
    return i4;
  }
  
  private static void parseTruns(Atom.ContainerAtom paramContainerAtom, TrackBundle paramTrackBundle, long paramLong, int paramInt)
  {
    int i = 0;
    int m = 0;
    paramContainerAtom = paramContainerAtom.leafChildren;
    int i1 = paramContainerAtom.size();
    int j = 0;
    Object localObject;
    int n;
    while (j < i1)
    {
      localObject = (Atom.LeafAtom)paramContainerAtom.get(j);
      n = m;
      k = i;
      if (((Atom.LeafAtom)localObject).type == Atom.TYPE_trun)
      {
        localObject = ((Atom.LeafAtom)localObject).data;
        ((ParsableByteArray)localObject).setPosition(12);
        int i2 = ((ParsableByteArray)localObject).readUnsignedIntToInt();
        n = m;
        k = i;
        if (i2 > 0)
        {
          n = m + i2;
          k = i + 1;
        }
      }
      j += 1;
      m = n;
      i = k;
    }
    paramTrackBundle.currentTrackRunIndex = 0;
    paramTrackBundle.currentSampleInTrackRun = 0;
    paramTrackBundle.currentSampleIndex = 0;
    paramTrackBundle.fragment.initTables(i, m);
    j = 0;
    int k = 0;
    i = 0;
    while (i < i1)
    {
      localObject = (Atom.LeafAtom)paramContainerAtom.get(i);
      n = j;
      m = k;
      if (((Atom.LeafAtom)localObject).type == Atom.TYPE_trun)
      {
        m = parseTrun(paramTrackBundle, j, paramLong, paramInt, ((Atom.LeafAtom)localObject).data, k);
        n = j + 1;
      }
      i += 1;
      j = n;
      k = m;
    }
  }
  
  private static void parseUuid(ParsableByteArray paramParsableByteArray, TrackFragment paramTrackFragment, byte[] paramArrayOfByte)
    throws ParserException
  {
    paramParsableByteArray.setPosition(8);
    paramParsableByteArray.readBytes(paramArrayOfByte, 0, 16);
    if (!Arrays.equals(paramArrayOfByte, PIFF_SAMPLE_ENCRYPTION_BOX_EXTENDED_TYPE)) {
      return;
    }
    parseSenc(paramParsableByteArray, 16, paramTrackFragment);
  }
  
  private void processAtomEnded(long paramLong)
    throws ParserException
  {
    while ((!this.containerAtoms.isEmpty()) && (((Atom.ContainerAtom)this.containerAtoms.peek()).endPosition == paramLong)) {
      onContainerAtomRead((Atom.ContainerAtom)this.containerAtoms.pop());
    }
    enterReadingAtomHeaderState();
  }
  
  private boolean readAtomHeader(ExtractorInput paramExtractorInput)
    throws IOException, InterruptedException
  {
    if (this.atomHeaderBytesRead == 0)
    {
      if (!paramExtractorInput.readFully(this.atomHeader.data, 0, 8, true)) {
        return false;
      }
      this.atomHeaderBytesRead = 8;
      this.atomHeader.setPosition(0);
      this.atomSize = this.atomHeader.readUnsignedInt();
      this.atomType = this.atomHeader.readInt();
    }
    if (this.atomSize == 1L)
    {
      paramExtractorInput.readFully(this.atomHeader.data, 8, 8);
      this.atomHeaderBytesRead += 8;
      this.atomSize = this.atomHeader.readUnsignedLongToLong();
    }
    if (this.atomSize < this.atomHeaderBytesRead) {
      throw new ParserException("Atom size less than header length (unsupported).");
    }
    long l = paramExtractorInput.getPosition() - this.atomHeaderBytesRead;
    if (this.atomType == Atom.TYPE_moof)
    {
      int j = this.trackBundles.size();
      int i = 0;
      while (i < j)
      {
        TrackFragment localTrackFragment = ((TrackBundle)this.trackBundles.valueAt(i)).fragment;
        localTrackFragment.atomPosition = l;
        localTrackFragment.auxiliaryDataPosition = l;
        localTrackFragment.dataPosition = l;
        i += 1;
      }
    }
    if (this.atomType == Atom.TYPE_mdat)
    {
      this.currentTrackBundle = null;
      this.endOfMdatPosition = (this.atomSize + l);
      if (!this.haveOutputSeekMap)
      {
        this.extractorOutput.seekMap(new SeekMap.Unseekable(this.durationUs));
        this.haveOutputSeekMap = true;
      }
      this.parserState = 2;
      return true;
    }
    if (shouldParseContainerAtom(this.atomType))
    {
      l = paramExtractorInput.getPosition() + this.atomSize - 8L;
      this.containerAtoms.add(new Atom.ContainerAtom(this.atomType, l));
      if (this.atomSize == this.atomHeaderBytesRead) {
        processAtomEnded(l);
      }
    }
    for (;;)
    {
      return true;
      enterReadingAtomHeaderState();
      continue;
      if (shouldParseLeafAtom(this.atomType))
      {
        if (this.atomHeaderBytesRead != 8) {
          throw new ParserException("Leaf atom defines extended atom size (unsupported).");
        }
        if (this.atomSize > 2147483647L) {
          throw new ParserException("Leaf atom with length > 2147483647 (unsupported).");
        }
        this.atomData = new ParsableByteArray((int)this.atomSize);
        System.arraycopy(this.atomHeader.data, 0, this.atomData.data, 0, 8);
        this.parserState = 1;
      }
      else
      {
        if (this.atomSize > 2147483647L) {
          throw new ParserException("Skipping atom with length > 2147483647 (unsupported).");
        }
        this.atomData = null;
        this.parserState = 1;
      }
    }
  }
  
  private void readAtomPayload(ExtractorInput paramExtractorInput)
    throws IOException, InterruptedException
  {
    int i = (int)this.atomSize - this.atomHeaderBytesRead;
    if (this.atomData != null)
    {
      paramExtractorInput.readFully(this.atomData.data, 8, i);
      onLeafAtomRead(new Atom.LeafAtom(this.atomType, this.atomData), paramExtractorInput.getPosition());
    }
    for (;;)
    {
      processAtomEnded(paramExtractorInput.getPosition());
      return;
      paramExtractorInput.skipFully(i);
    }
  }
  
  private void readEncryptionData(ExtractorInput paramExtractorInput)
    throws IOException, InterruptedException
  {
    Object localObject1 = null;
    long l1 = Long.MAX_VALUE;
    int j = this.trackBundles.size();
    int i = 0;
    while (i < j)
    {
      TrackFragment localTrackFragment = ((TrackBundle)this.trackBundles.valueAt(i)).fragment;
      long l2 = l1;
      Object localObject2 = localObject1;
      if (localTrackFragment.sampleEncryptionDataNeedsFill)
      {
        l2 = l1;
        localObject2 = localObject1;
        if (localTrackFragment.auxiliaryDataPosition < l1)
        {
          l2 = localTrackFragment.auxiliaryDataPosition;
          localObject2 = (TrackBundle)this.trackBundles.valueAt(i);
        }
      }
      i += 1;
      l1 = l2;
      localObject1 = localObject2;
    }
    if (localObject1 == null)
    {
      this.parserState = 3;
      return;
    }
    i = (int)(l1 - paramExtractorInput.getPosition());
    if (i < 0) {
      throw new ParserException("Offset to encryption data was negative.");
    }
    paramExtractorInput.skipFully(i);
    ((TrackBundle)localObject1).fragment.fillEncryptionData(paramExtractorInput);
  }
  
  private boolean readSample(ExtractorInput paramExtractorInput)
    throws IOException, InterruptedException
  {
    Object localObject;
    int i;
    int j;
    Track localTrack;
    TrackOutput localTrackOutput;
    byte[] arrayOfByte;
    int k;
    if (this.parserState == 3)
    {
      if (this.currentTrackBundle == null)
      {
        localObject = getNextFragmentRun(this.trackBundles);
        if (localObject == null)
        {
          i = (int)(this.endOfMdatPosition - paramExtractorInput.getPosition());
          if (i < 0) {
            throw new ParserException("Offset to end of mdat was negative.");
          }
          paramExtractorInput.skipFully(i);
          enterReadingAtomHeaderState();
          return false;
        }
        j = (int)(localObject.fragment.trunDataPosition[localObject.currentTrackRunIndex] - paramExtractorInput.getPosition());
        i = j;
        if (j < 0)
        {
          Log.w("FragmentedMp4Extractor", "Ignoring negative offset to sample data.");
          i = 0;
        }
        paramExtractorInput.skipFully(i);
        this.currentTrackBundle = ((TrackBundle)localObject);
      }
      this.sampleSize = this.currentTrackBundle.fragment.sampleSizeTable[this.currentTrackBundle.currentSampleIndex];
      if (this.currentTrackBundle.fragment.definesEncryptionData)
      {
        this.sampleBytesWritten = appendSampleEncryptionData(this.currentTrackBundle);
        this.sampleSize += this.sampleBytesWritten;
        if (this.currentTrackBundle.track.sampleTransformation == 1)
        {
          this.sampleSize -= 8;
          paramExtractorInput.skipFully(8);
        }
        this.parserState = 4;
        this.sampleCurrentNalBytesRemaining = 0;
      }
    }
    else
    {
      localObject = this.currentTrackBundle.fragment;
      localTrack = this.currentTrackBundle.track;
      localTrackOutput = this.currentTrackBundle.output;
      j = this.currentTrackBundle.currentSampleIndex;
      if (localTrack.nalUnitLengthFieldLength == 0) {
        break label600;
      }
      arrayOfByte = this.nalLength.data;
      arrayOfByte[0] = 0;
      arrayOfByte[1] = 0;
      arrayOfByte[2] = 0;
      i = localTrack.nalUnitLengthFieldLength;
      k = 4 - localTrack.nalUnitLengthFieldLength;
    }
    for (;;)
    {
      if (this.sampleBytesWritten >= this.sampleSize) {
        break label643;
      }
      if (this.sampleCurrentNalBytesRemaining == 0)
      {
        paramExtractorInput.readFully(this.nalLength.data, k, i);
        this.nalLength.setPosition(0);
        this.sampleCurrentNalBytesRemaining = this.nalLength.readUnsignedIntToInt();
        this.nalStartCode.setPosition(0);
        localTrackOutput.sampleData(this.nalStartCode, 4);
        this.sampleBytesWritten += 4;
        this.sampleSize += k;
        if (this.cea608TrackOutput == null) {
          continue;
        }
        arrayOfByte = this.nalPayload.data;
        paramExtractorInput.peekFully(arrayOfByte, 0, 1);
        if ((arrayOfByte[0] & 0x1F) != 6) {
          continue;
        }
        this.nalPayload.reset(this.sampleCurrentNalBytesRemaining);
        paramExtractorInput.readFully(arrayOfByte, 0, this.sampleCurrentNalBytesRemaining);
        localTrackOutput.sampleData(this.nalPayload, this.sampleCurrentNalBytesRemaining);
        this.sampleBytesWritten += this.sampleCurrentNalBytesRemaining;
        this.sampleCurrentNalBytesRemaining = 0;
        m = NalUnitUtil.unescapeStream(arrayOfByte, this.nalPayload.limit());
        this.nalPayload.setPosition(1);
        this.nalPayload.setLimit(m);
        CeaUtil.consume(((TrackFragment)localObject).getSamplePresentationTime(j) * 1000L, this.nalPayload, this.cea608TrackOutput);
        continue;
        this.sampleBytesWritten = 0;
        break;
      }
      int m = localTrackOutput.sampleData(paramExtractorInput, this.sampleCurrentNalBytesRemaining, false);
      this.sampleBytesWritten += m;
      this.sampleCurrentNalBytesRemaining -= m;
    }
    label600:
    while (this.sampleBytesWritten < this.sampleSize)
    {
      i = localTrackOutput.sampleData(paramExtractorInput, this.sampleSize - this.sampleBytesWritten, false);
      this.sampleBytesWritten += i;
    }
    label643:
    long l2 = ((TrackFragment)localObject).getSamplePresentationTime(j) * 1000L;
    if (((TrackFragment)localObject).definesEncryptionData)
    {
      i = 1073741824;
      if (localObject.sampleIsSyncFrameTable[j] == 0) {
        break label824;
      }
      j = 1;
      label679:
      k = ((TrackFragment)localObject).header.sampleDescriptionIndex;
      paramExtractorInput = null;
      if (((TrackFragment)localObject).definesEncryptionData) {
        if (((TrackFragment)localObject).trackEncryptionBox == null) {
          break label829;
        }
      }
    }
    label824:
    label829:
    for (paramExtractorInput = ((TrackFragment)localObject).trackEncryptionBox.keyId;; paramExtractorInput = localTrack.sampleDescriptionEncryptionBoxes[k].keyId)
    {
      long l1 = l2;
      if (this.timestampAdjuster != null) {
        l1 = this.timestampAdjuster.adjustSampleTimestamp(l2);
      }
      localTrackOutput.sampleMetadata(l1, i | j, this.sampleSize, 0, paramExtractorInput);
      while (!this.pendingMetadataSampleInfos.isEmpty())
      {
        paramExtractorInput = (MetadataSampleInfo)this.pendingMetadataSampleInfos.removeFirst();
        this.pendingMetadataSampleBytes -= paramExtractorInput.size;
        this.eventMessageTrackOutput.sampleMetadata(paramExtractorInput.presentationTimeDeltaUs + l1, 1, paramExtractorInput.size, this.pendingMetadataSampleBytes, null);
      }
      i = 0;
      break;
      j = 0;
      break label679;
    }
    paramExtractorInput = this.currentTrackBundle;
    paramExtractorInput.currentSampleIndex += 1;
    paramExtractorInput = this.currentTrackBundle;
    paramExtractorInput.currentSampleInTrackRun += 1;
    if (this.currentTrackBundle.currentSampleInTrackRun == localObject.trunLength[this.currentTrackBundle.currentTrackRunIndex])
    {
      paramExtractorInput = this.currentTrackBundle;
      paramExtractorInput.currentTrackRunIndex += 1;
      this.currentTrackBundle.currentSampleInTrackRun = 0;
      this.currentTrackBundle = null;
    }
    this.parserState = 3;
    return true;
  }
  
  private static boolean shouldParseContainerAtom(int paramInt)
  {
    return (paramInt == Atom.TYPE_moov) || (paramInt == Atom.TYPE_trak) || (paramInt == Atom.TYPE_mdia) || (paramInt == Atom.TYPE_minf) || (paramInt == Atom.TYPE_stbl) || (paramInt == Atom.TYPE_moof) || (paramInt == Atom.TYPE_traf) || (paramInt == Atom.TYPE_mvex) || (paramInt == Atom.TYPE_edts);
  }
  
  private static boolean shouldParseLeafAtom(int paramInt)
  {
    return (paramInt == Atom.TYPE_hdlr) || (paramInt == Atom.TYPE_mdhd) || (paramInt == Atom.TYPE_mvhd) || (paramInt == Atom.TYPE_sidx) || (paramInt == Atom.TYPE_stsd) || (paramInt == Atom.TYPE_tfdt) || (paramInt == Atom.TYPE_tfhd) || (paramInt == Atom.TYPE_tkhd) || (paramInt == Atom.TYPE_trex) || (paramInt == Atom.TYPE_trun) || (paramInt == Atom.TYPE_pssh) || (paramInt == Atom.TYPE_saiz) || (paramInt == Atom.TYPE_saio) || (paramInt == Atom.TYPE_senc) || (paramInt == Atom.TYPE_uuid) || (paramInt == Atom.TYPE_sbgp) || (paramInt == Atom.TYPE_sgpd) || (paramInt == Atom.TYPE_elst) || (paramInt == Atom.TYPE_mehd) || (paramInt == Atom.TYPE_emsg);
  }
  
  public void init(ExtractorOutput paramExtractorOutput)
  {
    this.extractorOutput = paramExtractorOutput;
    if (this.sideloadedTrack != null)
    {
      paramExtractorOutput = new TrackBundle(paramExtractorOutput.track(0));
      paramExtractorOutput.init(this.sideloadedTrack, new DefaultSampleValues(0, 0, 0, 0));
      this.trackBundles.put(0, paramExtractorOutput);
      maybeInitExtraTracks();
      this.extractorOutput.endTracks();
    }
  }
  
  public int read(ExtractorInput paramExtractorInput, PositionHolder paramPositionHolder)
    throws IOException, InterruptedException
  {
    for (;;)
    {
      switch (this.parserState)
      {
      default: 
        if (readSample(paramExtractorInput)) {
          return 0;
        }
        break;
      case 0: 
        if (!readAtomHeader(paramExtractorInput)) {
          return -1;
        }
        break;
      case 1: 
        readAtomPayload(paramExtractorInput);
        break;
      case 2: 
        readEncryptionData(paramExtractorInput);
      }
    }
  }
  
  public void release() {}
  
  public void seek(long paramLong1, long paramLong2)
  {
    int j = this.trackBundles.size();
    int i = 0;
    while (i < j)
    {
      ((TrackBundle)this.trackBundles.valueAt(i)).reset();
      i += 1;
    }
    this.pendingMetadataSampleInfos.clear();
    this.pendingMetadataSampleBytes = 0;
    this.containerAtoms.clear();
    enterReadingAtomHeaderState();
  }
  
  public boolean sniff(ExtractorInput paramExtractorInput)
    throws IOException, InterruptedException
  {
    return Sniffer.sniffFragmented(paramExtractorInput);
  }
  
  @Retention(RetentionPolicy.SOURCE)
  public static @interface Flags {}
  
  private static final class MetadataSampleInfo
  {
    public final long presentationTimeDeltaUs;
    public final int size;
    
    public MetadataSampleInfo(long paramLong, int paramInt)
    {
      this.presentationTimeDeltaUs = paramLong;
      this.size = paramInt;
    }
  }
  
  private static final class TrackBundle
  {
    public int currentSampleInTrackRun;
    public int currentSampleIndex;
    public int currentTrackRunIndex;
    public DefaultSampleValues defaultSampleValues;
    public final TrackFragment fragment = new TrackFragment();
    public final TrackOutput output;
    public Track track;
    
    public TrackBundle(TrackOutput paramTrackOutput)
    {
      this.output = paramTrackOutput;
    }
    
    public void init(Track paramTrack, DefaultSampleValues paramDefaultSampleValues)
    {
      this.track = ((Track)Assertions.checkNotNull(paramTrack));
      this.defaultSampleValues = ((DefaultSampleValues)Assertions.checkNotNull(paramDefaultSampleValues));
      this.output.format(paramTrack.format);
      reset();
    }
    
    public void reset()
    {
      this.fragment.reset();
      this.currentSampleIndex = 0;
      this.currentTrackRunIndex = 0;
      this.currentSampleInTrackRun = 0;
    }
    
    public void updateDrmInitData(DrmInitData paramDrmInitData)
    {
      this.output.format(this.track.format.copyWithDrmInitData(paramDrmInitData));
    }
  }
}


/* Location:              ~/Downloads/fugu-opr2.170623.027-factory-d4be396e/fugu-opr2.170623.027/image-fugu-opr2.170623.027/TVLauncher/TVLauncher/TVLauncher-dex2jar.jar!/com/google/android/exoplayer2/extractor/mp4/FragmentedMp4Extractor.class
 * Java compiler version: 6 (50.0)
 * JD-Core Version:       0.7.1
 */