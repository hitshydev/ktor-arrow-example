package io.github.nomisrev.service

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import io.github.nomisrev.CommentNotFound
import io.github.nomisrev.DomainError
import io.github.nomisrev.NotArticleAuthor
import io.github.nomisrev.NotCommentAuthor
import io.github.nomisrev.repo.ArticleId
import io.github.nomisrev.repo.ArticlePersistence
import io.github.nomisrev.repo.FavouritePersistence
import io.github.nomisrev.repo.TagPersistence
import io.github.nomisrev.repo.UserId
import io.github.nomisrev.repo.UserPersistence
import io.github.nomisrev.routes.Article
import io.github.nomisrev.routes.Comment
import io.github.nomisrev.routes.FeedLimit
import io.github.nomisrev.routes.FeedOffset
import io.github.nomisrev.routes.MultipleArticlesResponse
import io.github.nomisrev.routes.Profile
import io.github.nomisrev.sqldelight.Articles
import io.github.nomisrev.sqldelight.Comments
import java.time.OffsetDateTime

data class CreateArticle(
  val userId: UserId,
  val title: String,
  val description: String,
  val body: String,
  val tags: Set<String>,
)

data class UpdateArticleInput(
  val slug: Slug,
  val userId: UserId,
  val title: String?,
  val description: String?,
  val body: String?,
)

data class GetFeed(val userId: UserId, val limit: Int, val offset: Int)

interface ArticleService {
  /** Creates a new article and returns the resulting Article */
  suspend fun createArticle(input: CreateArticle): Either<DomainError, Article>

  /** Get the user's feed which contains articles of the authors the user followed */
  suspend fun getUserFeed(input: GetFeed): MultipleArticlesResponse

  /** Get article by Slug */
  suspend fun getArticleBySlug(slug: Slug): Either<DomainError, Article>

  /** Update an article and return the updated Article */
  suspend fun updateArticle(input: UpdateArticleInput): Either<DomainError, Article>

  /** Delete an article by slug */
  suspend fun deleteArticle(slug: Slug, userId: UserId): Either<DomainError, Unit>

  suspend fun insertCommentForArticleSlug(
    slug: Slug,
    userId: UserId,
    comment: String,
  ): Either<DomainError, Comments>

  suspend fun getCommentsForSlug(slug: Slug): List<Comment>

  /** Delete a comment for an article */
  suspend fun deleteComment(slug: Slug, commentId: Long, userId: UserId): Either<DomainError, Unit>

  /** Favorite an article and return the updated article */
  suspend fun favoriteArticle(slug: Slug, userId: UserId): Either<DomainError, Article>

  /** Unfavorite an article and return the updated article */
  suspend fun unfavoriteArticle(slug: Slug, userId: UserId): Either<DomainError, Article>
}

fun articleService(
  slugGenerator: SlugGenerator,
  articlePersistence: ArticlePersistence,
  userPersistence: UserPersistence,
  tagPersistence: TagPersistence,
  favouritePersistence: FavouritePersistence,
): ArticleService =
  object : ArticleService {
    override suspend fun deleteArticle(slug: Slug, userId: UserId): Either<DomainError, Unit> =
      either {
        val article = articlePersistence.findArticleBySlug(slug).bind()
        ensure(article.author_id == userId) { NotArticleAuthor(userId.serial, slug.value) }
        articlePersistence.deleteArticle(slug).bind()
      }

    override suspend fun createArticle(input: CreateArticle): Either<DomainError, Article> =
      either {
        val slug =
          slugGenerator
            .generateSlug(input.title) { slug -> articlePersistence.exists(slug).not() }
            .bind()
        val createdAt = OffsetDateTime.now()
        val articleId =
          articlePersistence
            .create(
              input.userId,
              slug,
              input.title,
              input.description,
              input.body,
              createdAt,
              createdAt,
              input.tags,
            )
            .serial
        val user = userPersistence.select(input.userId).bind()
        Article(
          articleId,
          slug.value,
          input.title,
          input.description,
          input.body,
          Profile(user.username, user.bio, user.image, false),
          false,
          0,
          createdAt,
          createdAt,
          input.tags.toList(),
        )
      }

    override suspend fun updateArticle(input: UpdateArticleInput): Either<DomainError, Article> =
      either {
        val article = articlePersistence.findArticleBySlug(input.slug).bind()

        ensure(article.author_id != input.userId) {
          raise(NotArticleAuthor(input.userId.serial, input.slug.value))
        }

        val updatedArticle =
          articlePersistence
            .updateArticle(input.slug, input.title, input.description, input.body)
            .bind()

        val favorite = favouritePersistence.isFavorite(input.userId, article.id)
        article(updatedArticle, favorite)
      }

    override suspend fun getUserFeed(input: GetFeed): MultipleArticlesResponse {
      val articles =
        articlePersistence.feed(
          userId = input.userId,
          limit = FeedLimit(input.limit),
          offset = FeedOffset(input.offset),
        )

      return MultipleArticlesResponse(articles = articles, articlesCount = articles.size)
    }

    override suspend fun getArticleBySlug(slug: Slug): Either<DomainError, Article> = either {
      val article = articlePersistence.findArticleBySlug(slug).bind()
      // TODO: optional auth route check if user favorited
      article(article, false)
    }

    override suspend fun insertCommentForArticleSlug(
      slug: Slug,
      userId: UserId,
      comment: String,
    ): Either<DomainError, Comments> = either {
      val article = getArticleBySlug(slug).bind()
      articlePersistence.createCommentForArticleSlug(
        slug,
        userId,
        comment,
        ArticleId(article.articleId),
        OffsetDateTime.now(),
      )
    }

    override suspend fun getCommentsForSlug(slug: Slug): List<Comment> =
      articlePersistence.findCommentsForSlug(slug)

    override suspend fun deleteComment(
      slug: Slug,
      commentId: Long,
      userId: UserId,
    ): Either<DomainError, Unit> = either {
      val authorId = articlePersistence.findCommentAuthor(commentId)
      ensureNotNull(authorId) { CommentNotFound(commentId) }
      ensure(authorId == userId) { NotCommentAuthor(userId.serial, commentId) }
      articlePersistence.deleteComment(commentId, userId)
    }

    override suspend fun favoriteArticle(slug: Slug, userId: UserId): Either<DomainError, Article> =
      either {
        val article = articlePersistence.findArticleBySlug(slug).bind()
        val articleId = article.id

        favouritePersistence.favoriteArticle(userId, articleId)
        article(article, true)
      }

    override suspend fun unfavoriteArticle(
      slug: Slug,
      userId: UserId,
    ): Either<DomainError, Article> = either {
      val article = articlePersistence.findArticleBySlug(slug).bind()
      val articleId = article.id
      favouritePersistence.unfavoriteArticle(userId, articleId)
      article(article, false)
    }

    private suspend fun Raise<DomainError>.article(article: Articles, favorited: Boolean): Article {
      val user = userPersistence.select(article.author_id).bind()
      val articleTags = tagPersistence.selectTagsOfArticle(article.id)
      val favouriteCount = favouritePersistence.favoriteCount(article.id)
      return Article(
        article.id.serial,
        article.slug,
        article.title,
        article.description,
        article.body,
        Profile(user.username, user.bio, user.image, false),
        favorited,
        favouriteCount,
        article.createdAt,
        article.updatedAt,
        articleTags,
      )
    }
  }
