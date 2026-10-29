package app.orchard.tv.kids

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * One question topic. The parent ticks the topics a round draws from on the phone page: a
 * kindergarten round only adds and subtracts, a primary one also multiplies and divides, and both
 * have poems, words, idioms and fruits of their own.
 */
enum class QuizBank(val key: String) {
    KgMath("kg_math"),
    KgPoem("kg_poem"),
    KgWords("kg_words"),
    KgIdiom("kg_idiom"),
    KgFruit("kg_fruit"),
    PrimaryMath("primary_math"),
    PrimaryPoem("primary_poem"),
    PrimaryWords("primary_words"),
    PrimaryIdiom("primary_idiom"),
    PrimaryFruit("primary_fruit");

    companion object {
        /** The topic a stored key names, or null when the key belongs to an older set. */
        fun of(key: String?): QuizBank? = entries.firstOrNull { it.key == key }
    }
}

/** [id] names the question, so a round can leave out the ones asked earlier today. */
data class Question(val text: String, val options: List<String>, val answer: Int, val id: String = text)

/**
 * The question sets. Words and idioms come from assets/kids_questions.json; poems come from the
 * lines of assets/tang_poems.json, and arithmetic is drawn fresh each round, so two rounds do not
 * repeat each other even when the child plays for a while.
 */
object QuizBankLoader {
    private var loaded: Map<QuizBank, List<Question>>? = null
    private var poems: PoemData? = null

    private class PoemData(val pairs: List<Pair<String, String>>, val lines: Map<Int, List<String>>)

    fun all(context: Context): Map<QuizBank, List<Question>> {
        loaded?.let { return it }
        val map = runCatching {
            val json = context.assets.open("kids_questions.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            QuizBank.entries.associateWith { bank ->
                val array = root.optJSONArray(bank.key) ?: return@associateWith emptyList()
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val options = o.optJSONArray("options") ?: return@mapNotNull null
                    val texts = (0 until options.length()).map { options.optString(it) }
                    if (texts.isEmpty()) null
                    else Question(
                        o.optString("q"), texts, o.optInt("answer", 0).coerceIn(0, texts.lastIndex),
                        "${bank.key}:$i",
                    )
                }
            }
        }.getOrDefault(emptyMap())
        loaded = map
        return map
    }

    /**
     * [count] questions drawn from [topics], shuffled, each with its options shuffled too.
     * Questions whose id is in [asked] are left out; when a topic has fewer questions left than a
     * round needs, those asked before fill the rest rather than leaving the round short.
     */
    fun round(
        context: Context,
        topics: Collection<QuizBank>,
        count: Int,
        asked: Set<String> = emptySet(),
    ): List<Question> {
        val banks = topics.distinct().ifEmpty { listOf(QuizBank.KgMath) }
        val fresh = ArrayList<Question>(count * 4)
        val repeats = ArrayList<Question>()
        for (bank in banks) {
            val made = when (bank) {
                // A kindergarten child adds and subtracts within 20; a primary one within 100.
                QuizBank.KgMath -> mathQuestions(20, count * 4, "kg_math")
                QuizBank.PrimaryMath -> mathQuestions(100, count * 4, "primary_math")
                QuizBank.KgPoem -> poemQuestions(context, setOf(5), count * 4, asked)
                QuizBank.PrimaryPoem -> poemQuestions(context, setOf(5, 7), count * 4, asked)
                QuizBank.KgFruit, QuizBank.PrimaryFruit -> fruitQuestions()
                else -> all(context)[bank].orEmpty()
            }
            for (q in made) (if (q.id in asked) repeats else fresh).add(q)
        }
        val out = ArrayList<Question>(count)
        val used = HashSet<String>()
        // One question of each wording a round: a fruit set repeats its line for another answer.
        val seen = HashSet<String>()
        for (q in fresh.shuffled()) {
            if (out.size == count) break
            if (q.id in used || q.text in seen) continue
            used += q.id
            seen += q.text
            out += q
        }
        if (out.size < count) {
            for (q in repeats.shuffled()) {
                if (out.size == count) break
                if (q.id in used || q.text in seen) continue
                used += q.id
                seen += q.text
                out += q
            }
        }
        return out.map(::withShuffledOptions)
    }

    private fun withShuffledOptions(q: Question): Question {
        val correct = q.options.getOrNull(q.answer) ?: return q
        val options = q.options.shuffled()
        return q.copy(options = options, answer = options.indexOf(correct))
    }

    /** Addition and subtraction with positive operands, the answer never above [max]. */
    private fun mathQuestions(max: Int, count: Int, key: String): List<Question> {
        val out = ArrayList<Question>(count)
        val used = HashSet<String>()
        var guard = 0
        while (out.size < count && guard++ < count * 20) {
            val plus = Random.nextBoolean()
            val a: Int
            val b: Int
            val answer: Int
            if (plus) {
                answer = Random.nextInt(2, max + 1)
                a = Random.nextInt(1, answer)
                b = answer - a
            } else {
                a = Random.nextInt(2, max + 1)
                b = Random.nextInt(1, a)
                answer = a - b
            }
            val id = "$key:$a${if (plus) "+" else "-"}$b"
            if (!used.add(id)) continue
            val numbers = optionsFor(answer, max)
            out += Question(
                "$a ${if (plus) "+" else "-"} $b = ？",
                numbers.map { it.toString() },
                numbers.indexOf(answer),
                id,
            )
        }
        return out
    }

    /** Four distinct numbers, the answer among them, close enough to look plausible. */
    private fun optionsFor(answer: Int, max: Int): List<Int> {
        val pool = LinkedHashSet<Int>()
        pool += answer
        var step = 1
        while (pool.size < 4 && step <= max) {
            if (answer + step <= max) pool += answer + step
            if (pool.size < 4 && answer - step >= 0) pool += answer - step
            step++
        }
        return pool.shuffled()
    }

    /**
     * Fruit questions, made each round rather than stored: an emoji names a fruit, or a line of
     * fruit names asks which one is a fruit. Questions about one fruit share an id, so a round
     * asks a fruit only once and the next round leaves it out.
     */
    private fun fruitQuestions(): List<Question> {
        val out = ArrayList<Question>()
        for (fruit in FRUITS) {
            val name = fruit.name
            if (fruit.emoji != null) {
                val others = FRUITS.filter { it.name != name }.shuffled().take(3).map { it.name }
                val options = (others + name).shuffled()
                out += Question("${fruit.emoji} 是什么水果？", options, options.indexOf(name), "fruit:$name")
            }
            val options = (NOT_FRUIT.shuffled().take(3) + name).shuffled()
            out += Question("下面哪个是水果？", options, options.indexOf(name), "fruit:$name")
        }
        for (thing in NOT_FRUIT) {
            val options = (FRUITS.shuffled().take(3).map { it.name } + thing).shuffled()
            out += Question("下面哪个不是水果？", options, options.indexOf(thing), "fruit:not:$thing")
        }
        FRUIT_FACTS.forEachIndexed { i, fact ->
            val options = (fact.wrong + fact.answer).shuffled()
            out += Question(fact.question, options, options.indexOf(fact.answer), "fruit:fact:$i")
        }
        return out
    }

    /** One more fruit question: the answer, and three wrong answers a child would weigh. */
    private class Fact(val question: String, val answer: String, val wrong: List<String>)

    private val FRUIT_FACTS = listOf(
        Fact("香蕉成熟后通常是什么颜色？", "黄色", listOf("红色", "蓝色", "紫色")),
        Fact("哪种水果是黄色的、弯弯的？", "香蕉", listOf("苹果", "葡萄", "西瓜")),
        Fact("西瓜切开后里面的果肉通常是什么颜色？", "红色", listOf("蓝色", "黑色", "白色")),
        Fact("西瓜的籽通常长在哪里？", "果肉里", listOf("皮外面", "叶子下", "根里")),
        Fact("柠檬通常是什么味道？", "酸", listOf("甜", "苦", "咸")),
        Fact("哪种水果一串一串地长？", "葡萄", listOf("西瓜", "椰子", "菠萝")),
        Fact("葡萄可以做成什么？", "葡萄干", listOf("面粉", "牛奶", "豆腐")),
        Fact("桃子表面通常有什么？", "细毛", listOf("硬刺", "硬壳", "鳞片")),
        Fact("哪种水果外皮有硬刺、果肉是黄色的？", "菠萝", listOf("香蕉", "苹果", "梨")),
        Fact("哪种水果外面有硬壳，里面有白色果肉和汁水？", "椰子", listOf("苹果", "香蕉", "草莓")),
        Fact("猕猴桃切开后里面的果肉通常是什么颜色？", "绿色", listOf("红色", "蓝色", "白色")),
        Fact("樱桃通常是什么颜色？", "红色", listOf("蓝色", "黑色", "绿色")),
        Fact("草莓表面有什么？", "小籽", listOf("硬刺", "鳞片", "硬壳")),
        Fact("芒果的果肉通常是什么颜色？", "黄色", listOf("蓝色", "绿色", "黑色")),
        Fact("哪种水果剥开皮后一瓣一瓣的？", "橘子", listOf("香蕉", "西瓜", "椰子")),
        Fact("蓝莓通常是什么颜色？", "蓝色", listOf("红色", "黄色", "绿色")),
        Fact("荔枝的外壳摸起来怎么样？", "粗糙", listOf("光滑", "很软", "像针一样尖")),
        Fact("石榴里面是什么？", "很多小籽", listOf("一根果柄", "一片叶子", "一大块果肉")),
    )

    /** A fruit a child may be asked about; [emoji] is null for the ones the set has no sign for. */
    private class Fruit(val name: String, val emoji: String? = null)

    private val FRUITS = listOf(
        Fruit("苹果", "🍎"),
        Fruit("梨", "🍐"),
        Fruit("橘子", "🍊"),
        Fruit("柠檬", "🍋"),
        Fruit("香蕉", "🍌"),
        Fruit("西瓜", "🍉"),
        Fruit("葡萄", "🍇"),
        Fruit("草莓", "🍓"),
        Fruit("哈密瓜", "🍈"),
        Fruit("樱桃", "🍒"),
        Fruit("桃子", "🍑"),
        Fruit("芒果", "🥭"),
        Fruit("菠萝", "🍍"),
        Fruit("猕猴桃", "🥝"),
        Fruit("椰子", "🥥"),
        Fruit("蓝莓", "🫐"),
        Fruit("荔枝"),
        Fruit("龙眼"),
        Fruit("石榴"),
        Fruit("柿子"),
        Fruit("李子"),
        Fruit("柚子"),
        Fruit("枇杷"),
        Fruit("杨梅"),
        Fruit("木瓜"),
        Fruit("火龙果"),
        Fruit("山竹"),
        Fruit("菠萝蜜"),
    )

    /** What a child should tell apart from fruit, for the "which is not a fruit" questions. */
    private val NOT_FRUIT = listOf(
        "白菜", "土豆", "黄瓜", "茄子", "萝卜", "玉米", "米饭", "面包",
        "铅笔", "桌子", "鞋子", "汽车", "书包", "雨伞",
    )

    /** "「床前明月光」的下一句是？" with other poets' lines of the same length as the wrong answers. */
    private fun poemQuestions(
        context: Context,
        lengths: Set<Int>,
        count: Int,
        asked: Set<String>,
    ): List<Question> {
        val data = poemData(context)
        val picked = data.pairs.asSequence()
            .filter { it.first.length in lengths && it.second.length == it.first.length }
            .filter { "poem:${it.first}|${it.second}" !in asked }
            .shuffled()
            .take(count)
            .toList()
        return picked.map { (prev, next) ->
            val options = LinkedHashSet<String>()
            options += next
            for (line in data.lines[next.length].orEmpty().shuffled()) {
                if (options.size >= 4) break
                if (line != next && line != prev) options += line
            }
            if (options.size < 4) {
                for (lines in data.lines.values.shuffled()) {
                    for (line in lines.shuffled()) {
                        if (options.size >= 4) break
                        if (line != next && line != prev && line.length in lengths) options += line
                    }
                }
            }
            val shuffled = options.shuffled()
            Question(
                "“$prev”的下一句是？", shuffled, shuffled.indexOf(next), "poem:$prev|$next",
            )
        }
    }

    private fun poemData(context: Context): PoemData {
        poems?.let { return it }
        val data = runCatching {
            val json = context.assets.open("tang_poems.json").bufferedReader().use { it.readText() }
            val root = JSONArray(json)
            val pairs = LinkedHashSet<Pair<String, String>>()
            val lines = HashMap<Int, LinkedHashSet<String>>()
            for (i in 0 until root.length()) {
                val o = root.optJSONObject(i) ?: continue
                val array = o.optJSONArray("lines") ?: continue
                val poem = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                poem.forEach { lines.getOrPut(it.length) { LinkedHashSet() } += it }
                for (j in 0 until poem.lastIndex) {
                    if (poem[j] != poem[j + 1]) pairs += poem[j] to poem[j + 1]
                }
            }
            PoemData(pairs.toList(), lines.mapValues { it.value.toList() })
        }.getOrDefault(PoemData(emptyList(), emptyMap()))
        poems = data
        return data
    }
}
