package org.squeryl.test.musicdb

/*******************************************************************************
 * Copyright 2010 Maxime Lévesque
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***************************************************************************** */
import java.sql.SQLException
import java.sql.Timestamp

import org.squeryl._
import adapters._
import dsl._
import dsl.ast.{RightHandSideOfIn, BinaryOperatorNodeLogicalBoolean}
import framework._
import java.util.{Date, Calendar}

object Genre extends Enumeration {
  type Genre = Value
  val Jazz = Value(1, "Jazz")
  val Rock = Value(2, "Rock")
  val Latin = Value(3, "Latin")
  val Bluegrass = Value(4, "Bluegrass")
  val RenaissancePolyphony = Value(5, "RenaissancePolyphony")
}

object Tempo extends Enumeration {
  type Tempo = Value
  val Largo = Value(1, "Largo")
  val Allegro = Value(2, "Allegro")
  val Presto = Value(3, "Presto")
}

import Genre._

class MusicDbObject extends KeyedEntity[Int] {
  val id: Int = 0
  var timeOfLastUpdate = new Timestamp(System.currentTimeMillis)
}

class Person(var firstName:String, var lastName: String, val age: Option[Int]) extends MusicDbObject{
  def this() = this("", "", None)
}

class Song(val title: String, val authorId: Int, val interpretId: Int, val cdId: Int, var genre: Genre, var secondaryGenre: Option[Genre]) extends MusicDbObject {
  def this() = this("", 0, 0, 0, Genre.Bluegrass, Some(Genre.Rock))
}

class Cd(var title: String, var mainArtist: Int, var year: Int) extends MusicDbObject {
  override def toString = id+ ":" + title
}

class MusicDb extends Schema {

  import org.squeryl.PrimitiveTypeMode._

  val songs = table[Song]

  val artists = table[Person]

  val cds = table[Cd]

  override def drop = {
    Session.cleanupResources
    super.drop
  }
}

class TestData(schema : MusicDb){
  import schema._
  val herbyHancock = artists.insert(new Person("Herby", "Hancock", Some(68)))
  val ponchoSanchez = artists.insert(new Person("Poncho", "Sanchez", None))
  val mongoSantaMaria = artists.insert(new Person("Mongo", "Santa Maria", None))
  val alainCaron = artists.insert(new Person("Alain", "Caron", None))
  val hossamRamzy = artists.insert(new Person("Hossam", "Ramzy", None))

  val congaBlue = cds.insert(new Cd("Conga Blue", ponchoSanchez.id, 1998))
  val   watermelonMan = songs.insert(new Song("Watermelon Man", herbyHancock.id, ponchoSanchez.id, congaBlue.id, Jazz, Some(Latin)))
  val   besameMama = songs.insert(new Song("Besame Mama", mongoSantaMaria.id, ponchoSanchez.id, congaBlue.id, Latin, None))

  val freedomSoundAlbum = cds.insert(new Cd("Freedom Sound", ponchoSanchez.id, 1997))

  val   freedomSound = songs.insert(new Song("Freedom Sound", ponchoSanchez.id, ponchoSanchez.id, freedomSoundAlbum.id, Jazz, Some(Latin)))

  val expectedSongCountPerAlbum = List((congaBlue.title,2), (freedomSoundAlbum.title, 1))
}

abstract class MusicDbTestRun extends SchemaTester with QueryTester with RunTestsInsideTransaction {

  import org.squeryl.PrimitiveTypeMode._

  val schema = new MusicDb

  import schema._
  
  var sharedTestInstance : TestData = null
  override def prePopulate(){
    sharedTestInstance = new TestData(schema)
  }



  lazy val poncho =
   from(artists)(a =>
      where(a.firstName === "Poncho") select(a)
   )

  test("JoinWithCompute"){
    val testInstance = sharedTestInstance; import testInstance._
    
    val q =
      join(artists,songs.leftOuter)((a,s)=>
        groupBy(a.id, a.firstName)
        compute(countDistinct(s.map(_.id)))
        on(a.id === s.map(_.authorId))
      )

    val r = q.map(q0 => (q0.key._1, q0.measures)).toSet

    assertEquals(
      Set((herbyHancock.id, 1),
          (ponchoSanchez.id,1),
          (mongoSantaMaria.id,1),
          (alainCaron.id, 0),
          (hossamRamzy.id, 0)),
      r, 'testJoinWithCompute)

    passed('testJoinWithCompute)
  }

  test("OuterJoinWithSubQuery"){
    val testInstance = sharedTestInstance; import testInstance._

    val artistsQ = artists.where(_.firstName <> "zozo")

    val q =
      join(artistsQ,songs.leftOuter)((a,s)=>
        select((a,s))
        on(a.id === s.map(_.authorId))
      ).toList


    val artistIdsWithoutSongs = q.filter(_._2 == None).map(_._1.id).toSet

    assertEquals(
      Set(alainCaron.id,hossamRamzy.id),
      artistIdsWithoutSongs, 'testOuterJoinWithSubQuery)

    val artistIdsWithSongs = q.filter(_._2 != None).map(_._1.id).toSet
    
    assertEquals(
      Set(herbyHancock.id,ponchoSanchez.id,mongoSantaMaria.id),
      artistIdsWithSongs, 'testOuterJoinWithSubQuery)

    passed('testOuterJoinWithSubQuery)
  }  



  lazy val songsFeaturingPoncho =
    from(songs, artists)((s,a) =>
      where(a.firstName === "Poncho" and s.interpretId === a.id)
      select(s)
      orderBy(s.title, a.id desc)
    )

  lazy val songsFeaturingPonchoNestedInWhere =
    from(songs, artists)((s,a) =>
      where(
        s.interpretId in from(artists)(a => where(a.firstName === "Poncho") select(a.id))
      )
      select(s)
      orderBy(s.title asc)
    ).distinct



  def songCountPerAlbum(cds: Queryable[Cd]) =
    from(cds, songs)((cd, song) =>
      where(song.cdId === cd.id)
      groupBy(cd.title) compute(count)
      orderBy(cd.title)
    )

  lazy val songCountPerAlbumFeaturingPoncho = songCountPerAlbum(
      from(songs, artists, cds)((s, a, cd) =>
        where(a.firstName === "Poncho" and s.interpretId === a.id and s.cdId === cd.id)
        select(cd)
      ).distinct
    )

  lazy val songsFeaturingPonchoNestedInFrom =
    from(songs, poncho)((s,a) =>
      where(s.interpretId === a.id)
      select((s,a.firstName))
      orderBy(s.title)
    )

  def songCountPerAlbumId(cds: Queryable[Cd]) =
    from(cds, songs)((cd, song) =>
      where(song.cdId === cd.id)
      groupBy(cd.id) compute(count)
    )

  lazy val songCountPerAlbumIdJoinedWithAlbum  =
    from(songCountPerAlbumId(cds), cds)((sc, cd) =>
      where(sc.key === cd.id)
      select((cd.title, sc.measures))
      orderBy(cd.title)
    )

  lazy val songCountPerAlbumIdJoinedWithAlbumZ  =
    from(songCountPerAlbumId(cds), cds)((sc, cd) =>
      where(sc.key === cd.id)
      select((cd, sc))
      orderBy(cd.title)
    )


  def songCountPerAlbumIdJoinedWithAlbumNested =
    from(songCountPerAlbumIdJoinedWithAlbumZ)(q =>
      select((q._1.title,q._2.measures))
      orderBy(q._1.title)
    )

  //TODO: list2Queryable conversion using 'select x0 as x from dual union ...'
  def artistsInvolvedInSongs(songIds: List[Int]) =
    from(
      from(songs)(s =>
        where((s.authorId in songIds) or (s.interpretId in songIds))
        select(s)
      ),
      artists
    )((s,a) =>
      where(s.authorId === a.id or s.interpretId === a.id)
      select(a)
      orderBy(a.lastName desc)
    ).distinct

  def songsFeaturingPonchoNestedInWhereWithString =
    from(songs, artists)((s,a) =>
      where(
        s.title in from(songs)(s => where(s.id === 123) select(s.title))
      )
      select(s)
      orderBy(s.title asc)
    )

  def countCds(cds: Queryable[Cd]) =
    from(cds)(c => compute(count))

  def countCds2(cds: Queryable[Cd]) = cds.Count

  def avgSongCountForAllArtists =
    from(
      from(artists, songs)((a,s) =>
        where(s.authorId === a.id)
        groupBy(a.id) compute(count)
      )
    )((sonCountPerArtist) =>
        compute(avg(sonCountPerArtist.measures))
    )


  def assertionFailed(s: Symbol, actual: Any, expected: Any) =
    assert(actual == expected, ""+s+" failed, got " + actual + " expected " + expected)


  private def _innerTx(songId: Long) = inTransaction {

    songs.where(_.id === songId).single
  }

  test("LoopInNestedInTransaction") {
    inTransaction {
      songsFeaturingPoncho.foreach(s => _innerTx(s.id))
    }
  }
   test("Queries"){
//  def working = {
//    val testInstance = sharedTestInstance; import testInstance._
//
//    testTimestampPartialUpdate
//
//    testAggregateQueryOnRightHandSideOfInOperator
//
//    testAggregateComputeInSubQuery
//
//    testEnums
//
//    val dbAdapter = Session.currentSession.databaseAdapter
//
//    testOuterJoinWithSubQuery
//
//    testJoinWithCompute
//
//    testInTautology
//
//    testNotInTautology
//
//    testDynamicWhereClause1
//
//    testEnums
//
//    testTimestamp
//
//    testTimestampDownToMillis
//
//    testConcatFunc
//
//    testRegexFunctionSupport
//
//    testUpperAndLowerFuncs
//
//    testCustomRegexFunctionSupport
//
//
//
//    testLoopInNestedInTransaction
//
//    testBetweenOperator
//
//    if(! dbAdapter.isInstanceOf[MSSQLServer])
//      testPaginatedQuery1
//
//    testDynamicQuery1
//
//    testDynamicQuery2
//
//    testDeleteVariations
//
//    testKeyedEntityImplicitLookup
    val testInstance = sharedTestInstance; import testInstance._
    val q = songCountPerAlbumIdJoinedWithAlbumNested

    validateQuery('songCountPerAlbumIdJoinedWithAlbumNested, q,
      (t:(String,Long)) => (t._1,t._2),
      expectedSongCountPerAlbum)

    def basicSelectUsingWhereOnQueryable =
      artists.where(a=> a.id === testInstance.mongoSantaMaria.id)

    def basicSelectUsingWhereOnQueryableNested =
      basicSelectUsingWhereOnQueryable.where(a=> a.id === testInstance.mongoSantaMaria.id)

    validateQuery('basicSelectUsingWhereOnQueryable, basicSelectUsingWhereOnQueryable, (a:Person)=>a.id, List(mongoSantaMaria.id))

    validateQuery('basicSelectUsingWhereOnQueryableNested, basicSelectUsingWhereOnQueryableNested, (a:Person)=>a.id, List(mongoSantaMaria.id))

    validateQuery('poncho, poncho, (a:Person)=>a.lastName, List(ponchoSanchez.lastName))

    val ponchoSongs = List(besameMama.title, freedomSound.title, watermelonMan.title)

    validateQuery('songsFeaturingPoncho, songsFeaturingPoncho, (s:Song)=>s.title,
      ponchoSongs)

    validateQuery('songsFeaturingPonchoNestedInWhere, songsFeaturingPonchoNestedInWhere, (s:Song)=>s.title,
      ponchoSongs)

    validateQuery('songCountPerAlbum, songCountPerAlbum(cds),
      (g:GroupWithMeasures[String,Long]) => (g.key,g.measures),
      expectedSongCountPerAlbum)

    def yearOfCongaBluePlus1 =
      from(cds)(cd =>
        where(cd.title === testInstance.congaBlue.title)
        select(&(cd.year plus 1))
      )

    validateQuery('yearOfCongaBluePlus1, yearOfCongaBluePlus1, identity[Int], List(1999))

    validateQuery('songCountPerAlbumFeaturingPoncho, songCountPerAlbumFeaturingPoncho,
      (g:GroupWithMeasures[String,Long]) => (g.key,g.measures),
      List((congaBlue.title,2), (freedomSoundAlbum.title, 1))
    )

    validateQuery('songsFeaturingPonchoNestedInFrom, songsFeaturingPonchoNestedInFrom,
      (t:(Song,String)) => (t._1.id,t._2),
      List((besameMama.id, ponchoSanchez.firstName),
           (freedomSound.id, ponchoSanchez.firstName),
           (watermelonMan.id, ponchoSanchez.firstName)))

    def selfJoinNested3Level =
      from(
        from(
          from(artists)(a =>   where(a.id === testInstance.ponchoSanchez.id) select(a))
        )(a =>   select(a))
      )(a =>   select(a))

    def selfJoinNested4LevelPartialSelect =
      from(selfJoinNested3Level)(a => where(a.id gt -1) select(a.lastName))

    validateQuery('selfJoinNested4LevelPartialSelect, selfJoinNested4LevelPartialSelect,
      identity[String], List(ponchoSanchez.lastName))

    validateQuery('selfJoinNested3Level, selfJoinNested3Level, (a:Person)=>a.lastName, List(ponchoSanchez.lastName))

    validateQuery('songCountPerAlbumIdJoinedWithAlbum, songCountPerAlbumIdJoinedWithAlbum,
      (t:(String,Long)) => (t._1,t._2),
      expectedSongCountPerAlbum)

    validateQuery('artistsInvolvedInSongsm, artistsInvolvedInSongs(List(watermelonMan.id)),
      (a:Person) => a.id, List(ponchoSanchez.id, herbyHancock.id))

    validateQuery('countCds, countCds(cds), (m:Measures[Long]) => m.measures, List(2))

    validateQuery('countCds2, countCds2(cds), identity[Long], List(2))
  }


  test("exerciseASTRenderingOfExportSelectElements"){
    val nestedCountCDs = from(from(countCds(cds))(m => select(m)))(m => select(m))

    validateQuery('countCds, nestedCountCDs, (m:Measures[Long]) => m.measures, List(2))
  }

  implicit def sExpr[E <% StringExpression[_]](s: E) = new RegexCall(s)

  class RegexCall(left: StringExpression[_]) {

    def regexC(e: String)  = new BinaryOperatorNodeLogicalBoolean(left, e, "~")
  }

  test("CustomRegexFunctionSupport"){
    if(Session.currentSession.databaseAdapter.isInstanceOf[H2Adapter]) {

      val q =
        from(artists)(a=>
          where(a.firstName.regexC(".*on.*"))
          select(a.firstName)
          orderBy(a.firstName)
        )

      val testInstance = sharedTestInstance; import testInstance._

      assertEquals(List(mongoSantaMaria.firstName, ponchoSanchez.firstName), q.toList, 'testCustomRegexFunctionSupport)

      passed('testCustomRegexFunctionSupport)
    }
  }

  test("RegexFunctionSupport"){
    try {
      val q =
        from(artists)(a =>
          where(a.firstName.regex(".*on.*"))
          select (a.firstName)
          orderBy (a.firstName)
        )

      val testInstance = sharedTestInstance; import testInstance._

      assertEquals(List(mongoSantaMaria.firstName, ponchoSanchez.firstName), q.toList, 'testCustomRegexFunctionSupport)

      passed('testRegexFunctionSupport)
    }
    catch {
      case e: UnsupportedOperationException => println("testRegexFunctionSupport: regex not supported by database adapter")
    }
  }


  test("UpperAndLowerFuncs"){
    try {
        val q =
          from(artists)(a=>
            where(a.firstName.regex(".*on.*"))
            select(&(upper(a.firstName) || lower(a.firstName)))
            orderBy(a.firstName)
          )
        

        val testInstance = sharedTestInstance; import testInstance._

        val expected = List(mongoSantaMaria.firstName, ponchoSanchez.firstName).map(s=> s.toUpperCase + s.toLowerCase)

        assertEquals(expected, q.toList, 'testUpperAndLowerFuncs)

        passed('testUpperAndLowerFuncs)
    }
    catch {
      case e: UnsupportedOperationException => println("testUpperAndLowerFuncs: regex not supported by database adapter")
    }
  }


  test("ConcatFunc"){
    val testInstance = sharedTestInstance; import testInstance._

      val q =
        from(artists)(a=>
          where(a.firstName in(Seq(mongoSantaMaria.firstName, ponchoSanchez.firstName)))
          select(&(a.firstName || "zozo"))
          orderBy(a.firstName)
        )

      val expected = List(mongoSantaMaria.firstName, ponchoSanchez.firstName).map(s=> s + "zozo")

      assertEquals(expected, q.toList, 'testConcatFunc)

      passed('testConcatFunc)
    }

  test("Timestamp"){
    val testInstance = sharedTestInstance; import testInstance._

    var mongo = artists.where(_.firstName === mongoSantaMaria.firstName).single
    // round to 0 second : 
    mongo = _truncateTimestampInTimeOfLastUpdate(mongo)

    val tX1 = mongo.timeOfLastUpdate
    
    val cal = Calendar.getInstance
    cal.setTime(mongo.timeOfLastUpdate)
    cal.roll(Calendar.SECOND, 12);

    val tX2 = new Timestamp(cal.getTimeInMillis)

    mongo.timeOfLastUpdate = new Timestamp(cal.getTimeInMillis)
    

    artists.update(mongo)
    mongo = artists.where(_.firstName === mongoSantaMaria.firstName).single

    assertEquals(new Timestamp(cal.getTimeInMillis), mongo.timeOfLastUpdate, 'testTimestamp)

    val mustBeSome =
      artists.where(a =>
        a.firstName === mongoSantaMaria.firstName and
        //a.timeOfLastUpdate.between(createLeafNodeOfScalarTimestampType(tX1), createLeafNodeOfScalarTimestampType(tX2))
        a.timeOfLastUpdate.between(tX1, tX2)
      ).headOption

    assert(mustBeSome != None, "testTimestamp failed");

    passed('testTimestamp)
  }

  private def _truncateTimestampInTimeOfLastUpdate(p: Person) = {
    val t1 = p.timeOfLastUpdate

    val cal = Calendar.getInstance

    cal.setTime(t1)
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);    

    p.timeOfLastUpdate = new Timestamp(cal.getTimeInMillis)
    val testInstance = sharedTestInstance; import testInstance._
    artists.update(p)
    //cal

    val out = artists.where(_.firstName === mongoSantaMaria.firstName).single

    assert(new Timestamp(cal.getTimeInMillis) == out.timeOfLastUpdate)

    out
  }

  private def _rollTimestamp(t: Timestamp, partToRoll: Int, rollAmount: Int) = {
    val cal = Calendar.getInstance
    cal.setTime(t)
    cal.roll(partToRoll, rollAmount);
    new Timestamp(cal.getTimeInMillis)
  }

  test("TestTimestampImplicit"){
    val testInstance = sharedTestInstance; import testInstance._

    val t: Option[Timestamp] =
      from(artists)(a=>
        compute(min(a.timeOfLastUpdate))
      )
  }

  ignore("TimestampPartialUpdate"){
    val testInstance = sharedTestInstance; import testInstance._

    var mongo = artists.where(_.firstName === mongoSantaMaria.firstName).single
    // round to 0 second :
    mongo = _truncateTimestampInTimeOfLastUpdate(mongo)


    val cal = Calendar.getInstance
    cal.setTime(mongo.timeOfLastUpdate)
    cal.roll(Calendar.SECOND, 12);

    update(artists)(a=>
      where(a.id === mongo.id)
      set(a.timeOfLastUpdate := new Timestamp(cal.getTimeInMillis))
    )

    val res = artists.where(_.firstName === mongoSantaMaria.firstName).single.timeOfLastUpdate
    //val res = mongo.timeOfLastUpdate

    assertEquals(cal.getTime, res, 'testTimestampPartialUpdate)

    passed('testTimestampPartialUpdate)
  }

  test("TimestampDownToMillis"){

    // Oracle : http://forums.oracle.com/forums/thread.jspa?threadID=239634
    // MySql  : http://bugs.mysql.com/bug.php?id=8523
    // MSSQL  : http://stackoverflow.com/questions/2620627/ms-sql-datetime-precision-problem
    val testInstance = sharedTestInstance; import testInstance._
    
    val dbAdapter = Session.currentSession.databaseAdapter
    if(!dbAdapter.isInstanceOf[MSSQLServer] && !dbAdapter.isInstanceOf[OracleAdapter]) {// FIXME or investigate millisecond handling of each DB:

      var mongo = artists.where(_.firstName === mongoSantaMaria.firstName).single
      // round to 0 second :
      mongo = _truncateTimestampInTimeOfLastUpdate(mongo)

      val tX2 = _rollTimestamp(mongo.timeOfLastUpdate, Calendar.MILLISECOND, 12)

      mongo.timeOfLastUpdate = tX2

      artists.update(mongo)
      val shouldBeSome = artists.where(a => a.firstName === mongoSantaMaria.firstName and a.timeOfLastUpdate === tX2).headOption

      if(shouldBeSome == None) org.squeryl.internals.Utils.throwError('testTimestampDownToMillis + " failed.")

      mongo = shouldBeSome.get

      if(!dbAdapter.isInstanceOf[MySQLAdapter]) {
        assertEquals(tX2, mongo.timeOfLastUpdate, 'testTimestampDownToMillis)
      }
      
      passed('testTimestampDownToMillis)
    }
  }
  
  test("validateScalarQuery1") {
    val cdCount: Long = countCds2(cds)
    assert(cdCount == 2, "exprected 2, got " + cdCount + " from " + countCds2(cds))

  }

  test("validateScalarQueryConversion1") {
    
    val d:Option[Double] = avgSongCountForAllArtists
    //println("d=" + d)
    assert(d.get == 1.0, "expected " + 1.0 +"got "  +d)
  }

  test("Update1"){
    val testInstance = sharedTestInstance; import testInstance._

    var ac = artists.where(a=> a.id === alainCaron.id).single
    ac.lastName = "Karon"
    artists.update(ac)
    ac = artists.where(a=> a.id === alainCaron.id).single
    assert(ac.lastName == "Karon", 'testUpdate1 + " failed, expected Karon, got " + ac.lastName)
    passed('testUpdate1 )
  }

  test("KeyedEntityImplicitLookup"){
    val testInstance = sharedTestInstance; import testInstance._

    var ac = artists.lookup(alainCaron.id).get

    assert(ac.id == alainCaron.id, "expected " + alainCaron.id + " got " + ac.id)
    passed('testKeyedEntityImplicitLookup)
  }

  test("DeleteVariations"){
    val testInstance = sharedTestInstance; import testInstance._

    var artistForDelete = artists.insert(new Person("Delete", "Me", None))

    assert(artists.delete(artistForDelete.id), "delete returned false, expected true")

    assert(artists.lookup(artistForDelete.id) == None, "object still exist after delete")

    artistForDelete = artists.insert(new Person("Delete", "Me", None))

    var c = artists.deleteWhere(a => a.id === artistForDelete.id)

    assert(c == 1, "deleteWhere failed, expected 1 row delete count, got " + c)

    assert(artists.lookup(artistForDelete.id) == None, "object still exist after delete")

    passed('testDeleteVariations)
  }

  def inhibitedArtistsInQuery(inhibit: Boolean) =
    from(songs, artists.inhibitWhen(inhibit))((s,a) =>
      where(a.get.firstName === "Poncho" and s.interpretId === a.get.id)
      select(s)
      orderBy(s.title, a.get.id desc)
    )

  test("DynamicQuery1"){
    val testInstance = sharedTestInstance; import testInstance._
    val allSongs =
      from(songs)(s =>
        select(s)
        orderBy(s.title)
      ).toList.map(s => s.id)

    val q = inhibitedArtistsInQuery(true)
    //println(q.dumpAst)
    val songsInhibited = q.toList.map(s => s.id)

    assert(allSongs == songsInhibited, "query inhibition failed, expected "+allSongs+", got " + songsInhibited)

    val songsNotInhibited = inhibitedArtistsInQuery(false)

    val ponchoSongs = List(besameMama.title, freedomSound.title, watermelonMan.title)

    validateQuery('songsNotInhibited, songsNotInhibited, (s:Song)=>s.title,
      ponchoSongs)

    passed('testDynamicQuery1)
  }

  def inhibitedSongsInQuery(inhibit: Boolean) =
    from(songs.inhibitWhen(inhibit), artists)((s,a) =>
      where(a.firstName === "Poncho" and s.get.interpretId === a.id)
      select((s, a))
      orderBy(s.get.title, a.id desc)
    )

  test("DynamicQuery2"){
    val testInstance = sharedTestInstance; import testInstance._
    val q = inhibitedSongsInQuery(true)
    //println(q.dumpAst)
    val t = q.single
    val poncho = t._2

    assert(ponchoSanchez.id == poncho.id, 'inhibitedSongsInQuery + " failed, expected " + ponchoSanchez.id + " got " + poncho.id)

    assert(t._1 == None, "inhibited table in query should have returned None, returned " + t._1)

    val songArtistsTuples = inhibitedSongsInQuery(false)

    val expected =
      from(songs, artists)((s,a) =>
        where(a.firstName === "Poncho" and s.interpretId === a.id)
        select((s.id, a.id))
        orderBy(s.title, a.id desc)
      )

    validateQuery('inhibitedSongsInQuery, songArtistsTuples,  (t:(Option[Song],Person)) => (t._1.get.id, t._2.id),
      expected.toList
    )

    passed('testDynamicQuery2)
  }

  test("PaginatedQuery1"){
    val testInstance = sharedTestInstance; import testInstance._
    val q = from(artists)(a =>
        select(a)
        orderBy(a.firstName asc)
      )

    val p1 = q.page(0, 2).map(a=>a.firstName).toList
    val p2 = q.page(2, 2).map(a=>a.firstName).toList
    val p3 = q.page(4, 2).map(a=>a.firstName).toList


    val ep1 = List(alainCaron.firstName, herbyHancock.firstName)
    val ep2 = List(hossamRamzy.firstName, mongoSantaMaria.firstName)
    val ep3 = List(ponchoSanchez.firstName)

    assertionFailed('testPaginatedQuery1, p1, ep1)
    assertionFailed('testPaginatedQuery1, p2, ep2)
    assertionFailed('testPaginatedQuery1, p3, ep3)

    passed('testPaginatedQuery1)
  }


  private def _betweenArtists(s1: String, s2: String) =
     from(artists)(a =>
       where(a.firstName between(s1, s2))
       select(a)
       orderBy(a.firstName asc)
     ).map(a=>a.firstName).toList


  test("BetweenOperator"){
    val testInstance = sharedTestInstance; import testInstance._
    val p1 = _betweenArtists(alainCaron.firstName, herbyHancock.firstName)
    val p2 = _betweenArtists(hossamRamzy.firstName, mongoSantaMaria.firstName)
    val p3 = _betweenArtists(ponchoSanchez.firstName, "Zaza Napoli")

    val ep1 = List(alainCaron.firstName, herbyHancock.firstName)
    val ep2 = List(hossamRamzy.firstName, mongoSantaMaria.firstName)
    val ep3 = List(ponchoSanchez.firstName)

    assertionFailed('testBetweenOperator, p1, ep1)
    assertionFailed('testBetweenOperator, p2, ep2)
    assertionFailed('testBetweenOperator, p3, ep3)

    passed('testBetweenOperator)
  }

//  test("leakTest"){
//
//    for(i <- 1 to 5000) {
//
//      new Thread(new Runnable {
//        def run = {
//          transaction {
//
//          //Session.currentSession.setLogger(println(_))
//          from(artists)(a => select(a)).toList
//        }
//        }
//      }).start
//    }
//  }
  
  
  test("Enums"){
    val testInstance = sharedTestInstance; import testInstance._
    val testAssemblaIssue9 =
      from(songs)(s =>
        where(s.genre in (
           from(songs)(s2 => select(s2.genre))
        ))
        select(s.genre)
      )

    testAssemblaIssue9.map(_.id).toSet

    //val md = songs.posoMetaData.findFieldMetaDataForProperty("genre").get
    //val z = md.canonicalEnumerationValueFor(2)

    val q = songs.where(_.genre === Jazz).map(_.id).toSet

    assertEquals(q, Set(watermelonMan.id, freedomSound.id), "testEnum failed")

    var wmm = songs.where(_.id === watermelonMan.id).single
    
    wmm.genre = Genre.Latin

    //loggerOn
    songs.update(wmm)

    wmm = songs.where(_.id === watermelonMan.id).single

    assertEquals(Genre.Latin, wmm.genre, "testEnum failed")

    update(songs)(s =>
      where(s.id === watermelonMan.id)
      set(s.genre := Genre.Jazz)
    )

    wmm = songs.where(_.id === watermelonMan.id).single

    assertEquals(Genre.Jazz, wmm.genre, "testEnum failed")

    //test for  Option[Enumeration] :

    val q2 = songs.where(_.secondaryGenre === Some(Genre.Latin)).map(_.id).toSet

    assertEquals(q2, Set(watermelonMan.id, freedomSound.id), "testEnum failed")

    wmm = songs.where(_.id === watermelonMan.id).single

    wmm.secondaryGenre = Some(Genre.Rock)

    //loggerOn
    songs.update(wmm)

    wmm = songs.where(_.id === watermelonMan.id).single

    assertEquals(Some(Genre.Rock), wmm.secondaryGenre, "testEnum failed")

    update(songs)(s =>
      where(s.id === watermelonMan.id)
      set(s.secondaryGenre := None)
    )

    wmm = songs.where(_.id === watermelonMan.id).single

    assertEquals(None, wmm.secondaryGenre, "testEnum failed")


    update(songs)(s =>
      where(s.id === watermelonMan.id)
      set(s.secondaryGenre := Some(Genre.Latin))
    )

    wmm = songs.where(_.id === watermelonMan.id).single

    assertEquals(Some(Genre.Latin), wmm.secondaryGenre, "testEnum failed")

    passed('testEnums)
  }

  test("DynamicWhereClause1"){
    val testInstance = sharedTestInstance; import testInstance._
    val allArtists = artists.toList

    val q1 = dynamicWhereOnArtists(None, None)

    assertEquals(allArtists.map(_.id).toSet, q1.map(_.id).toSet, 'testDynamicWhereClause1)

    val q2 = dynamicWhereOnArtists(None, Some("S%"))

    assertEquals(Set(ponchoSanchez.id, mongoSantaMaria.id), q2.map(_.id).toSet, 'testDynamicWhereClause1)

    val q3 = dynamicWhereOnArtists(Some("Poncho"), Some("S%"))

    assertEquals(Set(ponchoSanchez.id), q3.map(_.id).toSet, 'testDynamicWhereClause1)

    passed('testDynamicWhereClause1)

    /// Non compilation Test :

    val z1 = from(artists)(a => select(&(nvl(a.age,a.age)))).toList : List[Option[Int]]

    val z2 = from(artists)(a => select(&(nvl(a.age,0)))).toList : List[Int]
  }

  def dynamicWhereOnArtists(firstName: Option[String], lastName: Option[String]) =
      from(artists)(a =>
        where(
          (a.firstName === firstName.?) and
          (a.lastName like lastName.?)
        )
        select(a)
      )

  test("testSQLCaseNumerical1") {

    val testInstance = sharedTestInstance; import testInstance._


//    val ps =
//      Session.currentSession.connection.prepareStatement(
//        "Select " +
//        "  (case" +
//        "    when (a.streetname = ?) then cast(? as int)" +
//        "    when (a.streetname = ?) then cast(? as int)" +
//        "    else cast(? as int)" +
//        "    end) as v3 " +
//        "From" +
//        "  address a")
//
//    ps.setString(1,"z")
//    ps.setInt(2,1)
//    ps.setString(3,"q")
//    ps.setInt(4,2)
//    ps.setInt(5,3)
//
//    ps.execute()

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf
             when(a.firstName === "Herby", herbyHancock.id)
             when(a.firstName === "Poncho", ponchoSanchez.id)
             when(a.firstName === "Mongo", mongoSantaMaria.id)
             otherwise(-12345L))
          ))
      )

    q : Query[(String,Int,Long)]

    val list = q.toList

    val (trio, other) = list.partition(_._3 != -12345)

    assert(other.size == 2)

    assert(other.map(_._3).toSet == Set(-12345L))
    assert(trio.size == 3)

    val f = trio.filter(x => x._2 != x._3)

    assert(f == Nil)
  }

  test("testSQLCaseNumerical2") {

    val testInstance = sharedTestInstance; import testInstance._

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf
             when(a.firstName === "Herby", herbyHancock.id)
             when(a.firstName === "Poncho", BigDecimal(ponchoSanchez.id))
             when(a.firstName === "Mongo", mongoSantaMaria.id)
             otherwise(-12345L))
          ))
      )

    q : Query[(String,Int,BigDecimal)]

    val list = q.toList

    val (trio, other) = list.partition(_._3 != -12345)

    assert(other.size == 2)

    assert(other.map(_._3).toSet == Set(-12345L))
    assert(trio.size == 3)

    val f = trio.filter(x => x._2 != x._3)

    assert(f == Nil)
  }

  test("testSQLCaseNumerical3") {

    val testInstance = sharedTestInstance; import testInstance._

    //val noDouble = Option[Double] = None

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf
             when(a.firstName === "Herby", herbyHancock.id : Double)
             when(a.firstName === "Poncho", None : Option[Float])
             when(a.firstName === "Mongo", mongoSantaMaria.id)
             otherwise(-12345L))
          ))
      )

    q : Query[(String,Int,Option[Double])]

    val list = q.toList

    val (trio, other) = list.partition(_._3 != Some(-12345))

    assert(other.size == 2)

    assert(other.map(_._3).toSet == Set(Some(-12345D)))
    assert(trio.size == 3)

    assert(trio.find(_._3 == None).get._1 == "Poncho")

    assert(trio.map(_._1).toSet == Set("Herby", "Poncho", "Mongo"))
  }

  test("testSQLCaseNonNumerical1") {

    val testInstance = sharedTestInstance; import testInstance._

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf
             when(a.firstName === "Herby", a.firstName || "A")
             when(a.firstName === "Poncho", a.firstName  || "B")
             when(a.firstName === "Mongo", a.firstName || "C")
             otherwise("Z"))
          ))
      )

    q : Query[(String,Int,String)]

    _validateSQLCaseNonNumericalResult(q.toList)
  }

  private def _validateSQLCaseNonNumericalResult(list : Iterable[(String,Int,String)]) = {
    val (trio, other) = list.partition(_._3 != "Z")

    assert(other.size == 2)
    assert(trio.size == 3)

    assert(other.map(_._3).toSet == Set("Z"))

    assert(trio.map(_._3).toSet == Set("HerbyA", "PonchoB", "MongoC"))
  }

  test("testSQLCaseNonNumerical2") {

    val testInstance = sharedTestInstance; import testInstance._

    val q0 =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf
             when(a.firstName === "Herby", (Some(a.firstName): Option[String]) || "A")
             when(a.firstName === "Poncho", a.firstName  || "B")
             when(a.firstName === "Mongo", a.firstName || "C")
             otherwise("Z"))
          ))
      )

    q0 : Query[(String,Int,Option[String])]

    val q = q0.map((z:(String,Int,Option[String])) => (z._1, z._2, z._3.get))

    _validateSQLCaseNonNumericalResult(q.toList)
  }

  test("testSQLCaseNonNumerical3") {

    val testInstance = sharedTestInstance; import testInstance._

    val q0 =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf
             when(a.firstName === "Herby", None : Option[String])
             when(a.firstName === "Poncho", a.firstName  || "B")
             when(a.firstName === "Mongo", a.firstName || "C")
             otherwise("Z"))
          ))
      )

    q0 : Query[(String,Int,Option[String])]

    val q = q0.map((z:(String,Int,Option[String])) => (z._1, z._2, z._3.getOrElse("HerbyA")))

    _validateSQLCaseNonNumericalResult(q.toList)
  }

  test("testSQLMatchCaseNonNumerical2NonNumerical") {

    val testInstance = sharedTestInstance; import testInstance._

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf(a.firstName)
              when("Herby", a.firstName  || "A")
              when("Poncho", a.firstName  || "B")
              when("Mongo", a.firstName || "C")
              otherwise("Z")
          )
        ))
      )

    q : Query[(String,Int,String)]

    _validateSQLCaseNonNumericalResult(q.toList)
  }

  test("testSQLMatchCaseNonNumerical2Numerical") {

    val testInstance = sharedTestInstance; import testInstance._

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf(a.firstName)
              when("Herby", BigDecimal(1))
              when("Poncho", 2)
              when("Mongo", 3)
              otherwise(4)
          )
        ))
      )

    q : Query[(String,Int,BigDecimal)]

    val z = q.map(x => (x._1, x._2, x._3.intValue)).map(x =>
     (x._1,
      x._2,
      x._3 match {
        case 1 => "HerbyA"
        case 2 => "PonchoB"
        case 3 => "MongoC"
        case 4 => "Z"
      }))

    _validateSQLCaseNonNumericalResult(z)
  }

  test("testSQLMatchCaseNumerical2Numerical") {

    val testInstance = sharedTestInstance; import testInstance._
    val option2: Option[Int] = Some(2)

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf(a.id)
              when(herbyHancock.id, BigDecimal(1))
              when(ponchoSanchez.id, option2)
              when(mongoSantaMaria.id, 3)
              otherwise(4)
          )
        ))
      )

    q : Query[(String,Int,Option[BigDecimal])]

    val z = q.map(x => (x._1, x._2, x._3.get.intValue)).map(x =>
     (x._1,
      x._2,
      x._3 match {
        case 1 => "HerbyA"
        case 2 => "PonchoB"
        case 3 => "MongoC"
        case 4 => "Z"
      }))

    _validateSQLCaseNonNumericalResult(z)
  }

  test("testSQLMatchCaseNumericalWithOption2Numerical") {

    val testInstance = sharedTestInstance; import testInstance._
    val option2: Option[Int] = Some(2)

    val q =
      from(artists)(a =>
        select((
          &(a.firstName),
          &(a.id),
          &(caseOf(a.id)
              when(herbyHancock.id, BigDecimal(1))
              when(None : Option[Int], -3)
              when(ponchoSanchez.id, option2)
              when(mongoSantaMaria.id, 3)
              otherwise(4)
          )
        ))
      )

    q : Query[(String,Int,Option[BigDecimal])]

    val z = q.map(x => (x._1, x._2, x._3.get.intValue)).map(x =>
     (x._1,
      x._2,
      x._3 match {
        case 1 => "HerbyA"
        case 2 => "PonchoB"
        case 3 => "MongoC"
        case 4 => "Z"
      }))

    _validateSQLCaseNonNumericalResult(z)
  }

  test("InTautology"){

    val q = artists.where(_.firstName in Nil).toList

    assertEquals(Nil, q, 'testInTautology)

    passed('testInTautology)
  }

  test("NotInTautology"){

   val allArtists = artists.map(_.id).toSet

   val q = artists.where(_.firstName notIn Nil).map(_.id).toSet

    assertEquals(allArtists, q, 'testNotInTautology)

    passed('testNotInTautology)
  }

  test("AggregateQueryOnRightHandSideOfInOperator"){
    val testInstance = sharedTestInstance; import testInstance._
    val q1 =
      from(cds)(cd =>
        compute(min(cd.id))
      )

    val r1 = cds.where(_.id in q1).single

    assertEquals(congaBlue.id, r1.id, 'testAggregateQueryOnRightHandSideOfInOperator)

    val q2 =
      from(cds)(cd =>
        compute(min(cd.title))
      )

    val r2 = cds.where(_.title in q2).single
//    println(q2.statement)
//    println(cds.toList)
//    println(cds.where(_.title in q2).statement)
    assertEquals(congaBlue.title, r2.title, 'testAggregateQueryOnRightHandSideOfInOperator)

    // should compile (valid SQL even though phony...) :
    artists.where(_.age in from(artists)(a=> compute(count)))

    // should compile, since SQL allows comparing nullable cols against non nullable ones :
    artists.where(_.id in from(artists)(a=> compute(max(a.age))))

    //shouldn't compile :
    //artists.where(_.age in from(artists)(a=> compute(max(a.name))))

    passed('testAggregateQueryOnRightHandSideOfInOperator)
  }

  test("AggregateComputeInSubQuery"){
    val testInstance = sharedTestInstance; import testInstance._
    val q1 =
      from(cds)(cd =>
        compute(min(cd.id))
      )

    val q2 =
      from(cds, q1)((cd, q)=>
        where(cd.id === q.measures.get)
        select(cd)
      )

    val r1 = q2.single

    assertEquals(congaBlue.id, r1.id, 'testAggregateComputeInSubQuery)


    val q3 =
      from(cds)(cd =>
        groupBy(cd.id)
      )

    val q4 =
      from(cds, q3)((cd, q)=>
        where(cd.id === q.key)
        select(cd)
      )
    // should run without exception against the Db :
    q4.toList

    passed('testAggregateComputeInSubQuery)
  }
//  //class EnumE[A <: Enumeration#Value](val a: A) {
//  class EnumE[A](val a: A) {
//
//    def ===(b: EnumE[A]) = "not relevant"
//  }
//
//  //implicit def enum2EnumNode[A <: Enumeration#Value](e: A) = new EnumE[A](e)
//
//  implicit def enum2EnumNode[A <: Enumeration#Value](e: A) = new EnumE[A](e)
//
//  import Genre._
//  import Tempo._
//
//  val genre = Jazz
//  val tempo = Allegro
//
//  genre === Latin
//
//  tempo === Latin
//
}
